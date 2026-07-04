package com.ketch.internal.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ketch.DownloadConfig
import com.ketch.DownloadModel
import com.ketch.Logger
import com.ketch.NotificationConfig
import com.ketch.Status
import com.ketch.internal.database.DownloadDao
import com.ketch.internal.database.DownloadEntity
import com.ketch.internal.notification.DownloadNotificationManager
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.FileUtil.deleteFileIfExists
import com.ketch.internal.utils.UserAction
import com.ketch.internal.utils.WorkUtil
import com.ketch.internal.utils.WorkUtil.removeNotification
import com.ketch.internal.utils.WorkUtil.toJson
import com.ketch.internal.utils.toDownloadModel
import com.ketch.internal.worker.DownloadWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val workManager: WorkManager,
    private val downloadConfig: DownloadConfig,
    private val notificationConfig: NotificationConfig,
    private val logger: Logger
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.log(
            msg = "Exception in DownloadManager Scope: ${throwable.message}"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private val schedulerMutex = Mutex()
    private val locallyScheduledWorkIds = mutableSetOf<Int>()
    private val schedulableUserActions =
        setOf(
            UserAction.START.toString(),
            UserAction.RESUME.toString(),
            UserAction.RETRY.toString()
        )

    init {

        scope.launch {
            scheduleNextQueuedDownloads()
        }

        scope.launch {
            // Observe work infos, only for logging purpose
            workManager.getWorkInfosByTagFlow(DownloadConst.TAG_DOWNLOAD).flowOn(Dispatchers.IO)
                .collectLatest { workInfos ->
                    for (workInfo in workInfos) {
                        when (workInfo.state) {
                            WorkInfo.State.ENQUEUED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                logger.log(
                                    msg = "Download Queued. FileName: ${downloadEntity?.fileName}, " +
                                            "ID: ${downloadEntity?.id}"
                                )
                            }

                            WorkInfo.State.RUNNING -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                when (workInfo.progress.getString(DownloadConst.KEY_STATE)) {
                                    DownloadConst.STARTED ->
                                        logger.log(
                                            msg = "Download Started. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}, " +
                                                    "Size in bytes: ${downloadEntity?.totalBytes}"
                                        )

                                    DownloadConst.PROGRESS ->
                                        logger.log(
                                            msg = "Download in Progress. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}, " +
                                                    "Size in bytes: ${downloadEntity?.totalBytes}, " +
                                                    "downloadPercent: ${if (downloadEntity != null && downloadEntity.totalBytes.toInt() != 0) {
                                                        ((downloadEntity.downloadedBytes * 100) / downloadEntity.totalBytes).toInt()
                                                    } else {
                                                        0
                                                    }}%, " +
                                                    "downloadSpeedInBytesPerMilliSeconds: ${downloadEntity?.speedInBytePerMs} b/ms"
                                        )

                                }
                            }

                            WorkInfo.State.SUCCEEDED -> {



                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                logger.log(
                                    msg = "Download Success. FileName: ${downloadEntity?.fileName}, " +
                                            "ID: ${downloadEntity?.id}"
                                )
                                downloadEntity?.id?.let { scheduleAfterTerminalWork(it) }
                            }

                            WorkInfo.State.FAILED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                logger.log(
                                    msg = "Download Failed. FileName: ${downloadEntity?.fileName}, " +
                                            "ID: ${downloadEntity?.id}, " +
                                            "Reason: ${downloadEntity?.failureReason}"
                                )
                                downloadEntity?.id?.let { scheduleAfterTerminalWork(it) }
                            }

                            WorkInfo.State.CANCELLED -> {
                                val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                if (downloadEntity?.userAction == UserAction.PAUSE.toString()) {
                                    logger.log(
                                        msg = "Download Paused. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}"
                                    )
                                } else if (downloadEntity?.userAction == UserAction.CANCEL.toString()) {
                                    logger.log(
                                        msg = "Download Cancelled. FileName: ${downloadEntity.fileName}, " +
                                                "ID: ${downloadEntity.id}"
                                    )
                                }
                                downloadEntity?.id?.let { scheduleAfterTerminalWork(it) }
                            }

                            WorkInfo.State.BLOCKED -> {} // no use case
                        }
                    }
                }
        }
    }

    private suspend fun download(
        downloadRequest: DownloadRequest,
        replaceExistingWork: Boolean = false
    ) = schedulerMutex.withLock {
        if (!canStartWork(downloadRequest.id)) {
            upsertQueuedDownload(downloadRequest)
            return
        }

        enqueueDownloadWork(
            downloadRequest = downloadRequest,
            replaceExistingWork = replaceExistingWork,
            insertNewDownload = true
        )
    }

    private suspend fun upsertQueuedDownload(downloadRequest: DownloadRequest) {
        val existingDownload = downloadDao.find(downloadRequest.id)
        val now = System.currentTimeMillis()
        if (existingDownload != null) {
            downloadDao.update(
                existingDownload.copy(
                    status = Status.QUEUED.toString(),
                    uuid = "",
                    lastModified = now
                )
            )
        } else {
            downloadDao.insert(
                DownloadEntity(
                    url = downloadRequest.url,
                    path = downloadRequest.path,
                    fileName = downloadRequest.fileName,
                    tag = downloadRequest.tag,
                    id = downloadRequest.id,
                    headersJson = WorkUtil.hashMapToJson(downloadRequest.headers),
                    notificationParameter = downloadRequest.notificationParameter,
                    notificationTitle = downloadRequest.notificationTitle,
                    timeQueued = now,
                    status = Status.QUEUED.toString(),
                    uuid = "",
                    lastModified = now,
                    userAction = UserAction.START.toString(),
                    metaData = downloadRequest.metaData
                )
            )
            deleteFileIfExists(downloadRequest.path, downloadRequest.fileName)
        }
    }

    private suspend fun enqueueDownloadWork(
        downloadRequest: DownloadRequest,
        replaceExistingWork: Boolean,
        insertNewDownload: Boolean
    ) {
        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadConst.KEY_DOWNLOAD_REQUEST, downloadRequest.toJson())
                    .putString(DownloadConst.KEY_DOWNLOAD_CONFIG, downloadConfig.toJson())
                    .putString(DownloadConst.KEY_NOTIFICATION_CONFIG, notificationConfig.toJson())
                    .build()
            )
            .addTag(DownloadConst.TAG_DOWNLOAD)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        // Checks if download id already present in database
        if (downloadDao.find(downloadRequest.id) != null) {

            downloadDao.find(downloadRequest.id)?.copy(
                lastModified = System.currentTimeMillis()
            )?.let { downloadDao.update(it) }

            val downloadEntity = downloadDao.find(downloadRequest.id)

            // In case new download request is generated for already existing id in database
            // and work is not in progress, replace the uuid in database

            if (downloadEntity != null &&
                downloadEntity.uuid != downloadWorkRequest.id.toString() &&
                (replaceExistingWork ||
                    (downloadEntity.status != Status.QUEUED.toString() &&
                        downloadEntity.status != Status.PROGRESS.toString() &&
                        downloadEntity.status != Status.STARTED.toString()))
            ) {
                downloadDao.find(downloadRequest.id)?.copy(
                    uuid = downloadWorkRequest.id.toString(),
                    status = Status.QUEUED.toString(),
                    lastModified = System.currentTimeMillis()
                )?.let { downloadDao.update(it) }
            }
        } else {
            if (!insertNewDownload) {
                return
            }
            downloadDao.insert(
                DownloadEntity(
                    url = downloadRequest.url,
                    path = downloadRequest.path,
                    fileName = downloadRequest.fileName,
                    tag = downloadRequest.tag,
                    id = downloadRequest.id,
                    headersJson = WorkUtil.hashMapToJson(downloadRequest.headers),
                    notificationParameter = downloadRequest.notificationParameter,
                    notificationTitle = downloadRequest.notificationTitle,
                    timeQueued = System.currentTimeMillis(),
                    status = Status.QUEUED.toString(),
                    uuid = downloadWorkRequest.id.toString(),
                    lastModified = System.currentTimeMillis(),
                    userAction = UserAction.START.toString(),
                    metaData = downloadRequest.metaData
                )
            )
            deleteFileIfExists(downloadRequest.path, downloadRequest.fileName)
        }

        workManager.enqueueUniqueWork(
            downloadRequest.id.toString(),
            if (replaceExistingWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            downloadWorkRequest
        )
        locallyScheduledWorkIds.add(downloadRequest.id)
    }

    private suspend fun scheduleNextQueuedDownloads() = schedulerMutex.withLock {
        scheduleNextQueuedDownloadsLocked()
    }

    private suspend fun scheduleAfterTerminalWork(id: Int) = schedulerMutex.withLock {
        locallyScheduledWorkIds.remove(id)
        scheduleNextQueuedDownloadsLocked()
    }

    private suspend fun scheduleNextQueuedDownloadsLocked() {
        val capacity = downloadConfig.maxConcurrentDownloads - activeWorkCount()
        if (capacity <= 0) {
            return
        }

        downloadDao.getAllEntity()
            .asSequence()
            .filter { entity -> entity.status == Status.QUEUED.toString() }
            .filter { entity -> entity.userAction in schedulableUserActions }
            .filter { entity -> entity.id !in locallyScheduledWorkIds }
            .filterNot { entity -> hasActiveWork(entity.id) }
            .take(capacity)
            .forEach { entity ->
                val request = entity.toDownloadRequest()
                enqueueDownloadWork(
                    downloadRequest = request,
                    replaceExistingWork = true,
                    insertNewDownload = false
                )
            }
    }

    private suspend fun canStartWork(downloadId: Int): Boolean {
        return activeWorkCount(excludingDownloadId = downloadId) < downloadConfig.maxConcurrentDownloads
    }

    private suspend fun activeWorkCount(excludingDownloadId: Int? = null): Int {
        return downloadDao.getAllEntity()
            .asSequence()
            .filter { entity -> entity.id != excludingDownloadId }
            .filter { entity -> entity.userAction in schedulableUserActions }
            .filter { entity ->
                entity.status == Status.QUEUED.toString() ||
                    entity.status == Status.STARTED.toString() ||
                    entity.status == Status.PROGRESS.toString()
            }
            .count { entity -> entity.id in locallyScheduledWorkIds || hasActiveWork(entity.id) }
    }

    private fun hasActiveWork(id: Int): Boolean {
        return runCatching {
            workManager.getWorkInfosForUniqueWork(id.toString())
                .get()
                .any { info -> info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING }
        }.getOrDefault(false)
    }

    private fun DownloadEntity.toDownloadRequest(): DownloadRequest {
        return DownloadRequest(
            url = url,
            path = path,
            fileName = fileName,
            tag = tag,
            id = id,
            headers = WorkUtil.jsonToHashMap(headersJson),
            metaData = metaData,
            notificationParameter = notificationParameter,
            notificationTitle = notificationTitle
        )
    }

    private suspend fun resume(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.RESUME.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            download(
                DownloadRequest(
                    url = downloadEntity.url,
                    path = downloadEntity.path,
                    fileName = downloadEntity.fileName,
                    tag = downloadEntity.tag,
                    id = downloadEntity.id,
                    headers = WorkUtil.jsonToHashMap(downloadEntity.headersJson),
                    metaData = downloadEntity.metaData,
                    notificationParameter = downloadEntity.notificationParameter,
                    notificationTitle = downloadEntity.notificationTitle
                ),
                replaceExistingWork = true
            )
        }
    }

    private suspend fun cancel(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.CANCEL.toString(),
                    status = Status.CANCELLED.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            if (downloadEntity.status == Status.PAUSED.toString() ||
                downloadEntity.status == Status.FAILED.toString()
            ) { // Edge Case: When user cancel the download in pause or fail (terminating) state as work is already cancelled.

                downloadDao.find(downloadEntity.id)?.copy(
                    status = Status.CANCELLED.toString(),
                    lastModified = System.currentTimeMillis()
                )?.let { downloadDao.update(it) }
                deleteFileIfExists(downloadEntity.path, downloadEntity.fileName)
                DownloadNotificationManager(
                    context = context,
                    notificationConfig = notificationConfig,
                    requestId = id,
                    fileName = downloadEntity.notificationTitle,
                    notificationParameter = downloadEntity.notificationParameter,


                ).sendDownloadCancelledNotification()
            }
        }
        schedulerMutex.withLock {
            locallyScheduledWorkIds.remove(id)
        }
        workManager.cancelUniqueWork(id.toString())
    }

    private suspend fun pause(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.PAUSE.toString(),
                    status = Status.PAUSED.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
        }
        schedulerMutex.withLock {
            locallyScheduledWorkIds.remove(id)
        }
        workManager.cancelUniqueWork(id.toString())
    }

    private suspend fun retry(id: Int) {
        val downloadEntity = downloadDao.find(id)
        if (downloadEntity != null) {
            downloadDao.update(
                downloadEntity.copy(
                    userAction = UserAction.RETRY.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )
            download(
                DownloadRequest(
                    url = downloadEntity.url,
                    path = downloadEntity.path,
                    fileName = downloadEntity.fileName,
                    tag = downloadEntity.tag,
                    id = downloadEntity.id,
                    headers = WorkUtil.jsonToHashMap(downloadEntity.headersJson),
                    metaData = downloadEntity.metaData,
                    notificationParameter = downloadEntity.notificationParameter,
                    notificationTitle = downloadEntity.notificationTitle
                ),
                replaceExistingWork = true
            )
        }
    }

    private suspend fun findDownloadEntityFromUUID(uuid: UUID): DownloadEntity? {
        return downloadDao.getAllEntity().find { it.uuid == uuid.toString() }
    }

    fun resumeAsync(id: Int) {
        scope.launch {
            resume(id)
        }
    }

    fun resumeAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    resume(it.id)
                }
            }
        }
    }

    fun resumeAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                resume(it.id)
            }
        }
    }

    fun cancelAsync(id: Int) {
        scope.launch {
            cancel(id)
        }
    }

    fun cancelAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    cancel(it.id)
                }
            }
        }
    }

    fun cancelAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                cancel(it.id)
            }
        }
    }

    fun pauseAsync(id: Int) {
        scope.launch {
            pause(id)
        }
    }

    fun pauseAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    pause(it.id)
                }
            }
        }
    }

    fun pauseAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                pause(it.id)
            }
        }
    }

    fun retryAsync(id: Int) {
        scope.launch {
            retry(id)
        }
    }

    fun retryAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                if (it.tag == tag) {
                    retry(it.id)
                }
            }
        }
    }

    fun retryAllAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                retry(it.id)
            }
        }
    }

    fun clearDbAsync(id: Int) {
        scope.launch {
            cancel(id)
            val downloadEntity = downloadDao.find(id)
            val path = downloadEntity?.path
            val fileName = downloadEntity?.fileName
            if (path != null && fileName != null) {
                deleteFileIfExists(path, fileName)
            }
            removeNotification(context, id) // In progress notification
            removeNotification(context, id + 1) // Cancelled, Paused, Failed, Success notification
            downloadDao.remove(id)
        }
    }

    fun clearDbAsync(tag: String) {
        scope.launch {
            downloadDao.getAllEntityByTag(tag).forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteFileIfExists(path, fileName)
                }
                downloadDao.remove(it.id)
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
        }
    }

    fun clearAllDbAsync() {
        scope.launch {
            downloadDao.getAllEntity().forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteFileIfExists(path, fileName)
                }
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
            downloadDao.deleteAll()
        }
    }

    fun clearDbAsync(timeInMillis: Long) {
        scope.launch {
            downloadDao.getEntityTillTime(timeInMillis).forEach {
                cancel(it.id)
                val downloadEntity = downloadDao.find(it.id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null) {
                    deleteFileIfExists(path, fileName)
                }
                downloadDao.remove(it.id)
                removeNotification(context, it.id) // In progress notification
                removeNotification(context, it.id + 1) // Cancelled, Paused, Failed, Success notification
            }
        }
    }

    fun downloadAsync(downloadRequest: DownloadRequest) {
        scope.launch {
            download(downloadRequest)
        }
    }

    fun observeDownloadById(id: Int): Flow<DownloadModel> {
        return downloadDao.getEntityByIdFlow(id).filterNotNull().distinctUntilChanged().map { entity ->
            entity.toDownloadModel()
        }
    }

    fun observeDownloadsByTag(tag: String): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByTagFlow(tag).map { entityList ->
            entityList.map { entity ->
                entity.toDownloadModel()
            }
        }
    }

    fun observeAllDownloads(): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityFlow().map { entityList ->
            entityList.map { entity ->
                entity.toDownloadModel()
            }
        }
    }

    suspend fun getAllDownloads(): List<DownloadModel> {
        return downloadDao.getAllEntity().map { entity ->
            entity.toDownloadModel()
        }
    }


}
