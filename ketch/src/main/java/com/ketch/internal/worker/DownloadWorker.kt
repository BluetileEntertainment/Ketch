package com.ketch.internal.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ketch.Status
import com.ketch.internal.database.DatabaseInstance
import com.ketch.internal.download.DownloadTask
import com.ketch.internal.download.ApiResponseHeaderChecker
import com.ketch.internal.network.RetrofitInstance
import com.ketch.internal.notification.DownloadNotificationManager
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.ExceptionConst
import com.ketch.internal.utils.FileUtil
import com.ketch.internal.utils.UserAction
import com.ketch.internal.utils.WorkUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

internal class DownloadWorker(
    private val context: Context,
    private val workerParameters: WorkerParameters
) :
    CoroutineWorker(context, workerParameters) {

    companion object {
        private const val MAX_PERCENT = 100
    }

    private var downloadNotificationManager: DownloadNotificationManager? = null
    private val downloadDao = DatabaseInstance.getInstance(context).downloadDao()

    override suspend fun doWork(): Result {

        val downloadRequest =
            WorkUtil.jsonToDownloadRequest(
                inputData.getString(DownloadConst.KEY_DOWNLOAD_REQUEST)
                    ?: return Result.failure(
                        workDataOf(ExceptionConst.KEY_EXCEPTION to ExceptionConst.EXCEPTION_FAILED_DESERIALIZE)
                    )
            )

        val downloadConfig =
            WorkUtil.jsonToDownloadConfig(
                inputData.getString(DownloadConst.KEY_DOWNLOAD_CONFIG) ?: ""
            )

        val notificationConfig =
            WorkUtil.jsonToNotificationConfig(
                inputData.getString(DownloadConst.KEY_NOTIFICATION_CONFIG) ?: ""
            )

        val id = downloadRequest.id
        val url = downloadRequest.url
        val dirPath = downloadRequest.path
        val fileName = downloadRequest.fileName
        val headers = downloadRequest.headers
        val tag = downloadRequest.tag
        val notificationTitle = downloadRequest.notificationTitle
        val notificationParameter = downloadRequest.notificationParameter

        if (notificationConfig.enabled) {
            downloadNotificationManager = DownloadNotificationManager(
                context = context,
                notificationConfig = notificationConfig,
                requestId = id,
                fileName = notificationTitle.ifEmpty { fileName },
                notificationParameter = notificationParameter
            )
        }

        val downloadService = RetrofitInstance.getDownloadService(
            downloadConfig.connectTimeOutInMs,
            downloadConfig.readTimeOutInMs
        )

        return try {
            downloadNotificationManager?.sendUpdateNotification()?.let {
                setForeground(
                    it
                )
            }

            val latestETag =
                ApiResponseHeaderChecker(downloadRequest.url, downloadService, headers)
                    .getHeaderValue(DownloadConst.ETAG_HEADER) ?: ""

            val existingETag = downloadDao.find(id)?.eTag ?: ""

            if (latestETag != existingETag) {
                FileUtil.deleteFileIfExists(path = dirPath, name = fileName)
                downloadDao.find(id)?.copy(
                    eTag = latestETag,
                    lastModified = System.currentTimeMillis()
                )?.let { downloadDao.update(it) }
            }

            var progressPercentage = 0

            val totalLength = DownloadTask(
                url = url,
                path = dirPath,
                fileName = fileName,
                downloadService = downloadService
            ).download(
                headers = headers,
                onStart = { length ->

                    downloadDao.find(id)?.copy(
                        totalBytes = length,
                        status = Status.STARTED.toString(),
                        lastModified = System.currentTimeMillis()
                    )?.let { downloadDao.update(it) }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.STARTED
                        )
                    )
                },
                onProgress = { downloadedBytes, length, speed ->

                    val progress = if (length != 0L) {
                        ((downloadedBytes * 100) / length).toInt()
                    } else {
                        0
                    }

                    if (progressPercentage != progress) {

                        progressPercentage = progress

                        downloadDao.find(id)?.copy(
                            downloadedBytes = downloadedBytes,
                            speedInBytePerMs = speed,
                            status = Status.PROGRESS.toString(),
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }

                    }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.PROGRESS,
                            DownloadConst.KEY_PROGRESS to progress
                        )
                    )
                    downloadNotificationManager?.sendUpdateNotification(
                        progress = progress,
                        speedInBPerMs = speed,
                        length = length,
                        update = true
                    )?.let {
                        setForeground(
                            it
                        )
                    }
                }
            )

            downloadDao.find(id)?.copy(
                totalBytes = totalLength,
                status = Status.SUCCESS.toString(),
                lastModified = System.currentTimeMillis()
            )?.let { downloadDao.update(it) }

            downloadNotificationManager?.sendDownloadSuccessNotification(totalLength,notificationParameter)
            Result.success()
        } catch (e: Exception) {
            GlobalScope.launch {
                if (e is CancellationException) {
                    if (downloadDao.find(id)?.userAction == UserAction.PAUSE.toString()) {

                        downloadDao.find(id)?.copy(
                            status = Status.PAUSED.toString(),
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }
                        val downloadEntity = downloadDao.find(id)
                        if (downloadEntity != null) {
                            val currentProgress = if (downloadEntity.totalBytes != 0L) {
                                ((downloadEntity.downloadedBytes * MAX_PERCENT) / downloadEntity.totalBytes).toInt()
                            } else {
                                0
                            }
                            downloadNotificationManager?.sendDownloadPausedNotification(
                                currentProgress = currentProgress
                            )
                        }

                    } else {

                        downloadDao.find(id)?.copy(
                            status = Status.CANCELLED.toString(),
                            lastModified = System.currentTimeMillis()
                        )?.let { downloadDao.update(it) }
                        FileUtil.deleteFileIfExists(dirPath, fileName)
                        downloadNotificationManager?.sendDownloadCancelledNotification()

                    }
                } else {

                    downloadDao.find(id)?.copy(
                        status = Status.FAILED.toString(),
                        failureReason = e.message ?: "",
                        lastModified = System.currentTimeMillis()
                    )?.let { downloadDao.update(it) }
                    val downloadEntity = downloadDao.find(id)
                    if (downloadEntity != null) {
                        val currentProgress = if (downloadEntity.totalBytes != 0L) {
                            ((downloadEntity.downloadedBytes * MAX_PERCENT) / downloadEntity.totalBytes).toInt()
                        } else {
                            0
                        }
                        downloadNotificationManager?.sendDownloadFailedNotification(
                            currentProgress = currentProgress
                        )
                    }
                }
            }
            Result.failure(
                workDataOf(ExceptionConst.KEY_EXCEPTION to e.message)
            )
        }

    }

}
