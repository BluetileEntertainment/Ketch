package com.ketch

import android.content.Context
import androidx.work.WorkManager
import com.ketch.internal.database.DatabaseInstance
import com.ketch.internal.download.ApiResponseHeaderChecker
import com.ketch.internal.download.DownloadManager
import com.ketch.internal.download.DownloadRequest
import com.ketch.internal.network.RetrofitInstance
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.DownloadLogger
import com.ketch.internal.utils.FileUtil
import com.ketch.internal.utils.NotificationConst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Ketch: Core singleton class client interacts with
 *
 * How to initialize [Ketch] instance?
 *
 * ```
 * // Simplest way to initialize:
 * Ketch.builder().build(context)
 *
 * // Sample to initialize the library inside application class
 * class MainApplication : Application() {
 *
 *     lateinit var ketch: Ketch
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         ketch = Ketch.builder()
 *             .setDownloadConfig(DownloadConfig()) // optional
 *             .setNotificationConfig( // optional, Notification is off by default
 *                 NotificationConfig(
 *                     true,
 *                     smallIcon = R.drawable.ic_launcher_foreground
 *                 )
 *             )
 *             .enableLogs(true) // optional, logs are off by default
 *             .setLogger(...) // optional, pass your own logger implementation
 *             .build(this)
 *     }
 *
 * }
 *
 * // To use the library
 * ketch.download(url, path, fileName) // download
 * ketch.pause(id) // pause download
 * ketch.resume(id) // resume download
 * ketch.retry(id) // retry download
 * ketch.cancel(id) // cancel download
 * ketch.clearDb(id) // clear database and delete file
 *
 * // To observe the downloads
 * lifecycleScope.launch {
 *    repeatOnLifecycle(Lifecycle.State.STARTED) {
 *       ketch.observeDownloads().collect { downloadModelList ->
 *         // take appropriate action with observed list of [DownloadModel]
 *       }
 *    }
 * }
 *
 * ```
 *
 * JOURNEY OF SINGLE DOWNLOAD FILE:
 *
 * [Status.QUEUED] -> [Status.STARTED] -> [Status.PROGRESS] -> Download in progress
 * Terminating states: [Status.PAUSED], [Status.CANCELLED], [Status.FAILED], [Status.SUCCESS]
 *
 * @property context Application context
 * @property downloadConfig [DownloadConfig] to configure download related info
 * @property notificationConfig [NotificationConfig] to configure notification related info
 * @property logger [Logger] implementation to print logs
 * @constructor Create empty Ketch
 */
@Suppress("TooManyFunctions")
class Ketch private constructor(
    private val context: Context,
    private var downloadConfig: DownloadConfig,
    private var notificationConfig: NotificationConfig,
    private var logger: Logger
) {

    companion object {

        @Volatile
        private var ketchInstance: Ketch? = null

        fun builder() = Builder()

        class Builder {
            private var downloadConfig: DownloadConfig = DownloadConfig()
            private var notificationConfig: NotificationConfig = NotificationConfig(
                smallIcon = NotificationConst.DEFAULT_VALUE_NOTIFICATION_SMALL_ICON
            )
            private var logger: Logger = DownloadLogger(false)

            fun setDownloadConfig(config: DownloadConfig) = apply {
                this.downloadConfig = config
            }

            fun setNotificationConfig(config: NotificationConfig) = apply {
                this.notificationConfig = config
            }

            fun enableLogs(enable: Boolean) = apply {
                this.logger = DownloadLogger(enable)
            }

            fun setLogger(logger: Logger) = apply {
                this.logger = logger
            }

            @Synchronized
            fun build(context: Context): Ketch {
                if (ketchInstance == null) {
                    ketchInstance = Ketch(
                        context = context.applicationContext,
                        downloadConfig = downloadConfig,
                        notificationConfig = notificationConfig,
                        logger = logger
                    )
                }
                return ketchInstance!!
            }
        }
    }

    private val downloadManager = DownloadManager(
        context = context,
        downloadDao = DatabaseInstance.getInstance(context).downloadDao(),
        workManager = WorkManager.getInstance(context.applicationContext),
        downloadConfig = downloadConfig,
        notificationConfig = notificationConfig,
        logger = logger
    )

    /**
     * Download the content
     *
     * @param url Download url of the content
     * @param path Download path to store the downloaded file
     * @param fileName Name of the file to be downloaded
     * @param tag Optional tag for each download to group the download into category
     * @param metaData Optional metaData set for adding any extra download info
     * @param headers Optional headers sent when making api call for file download
     * @param notificationTitle Optional notification title
     * @param notificationParameter Optional notification parameter
     * @return Unique Download ID associated with current download
     */
    fun download(
        url: String,
        path: String,
        fileName: String = FileUtil.getFileNameFromUrl(url),
        tag: String = "",
        metaData: String = "",
        notificationTitle: String = "",
        notificationParameter: String = "",
        headers: HashMap<String, String> = hashMapOf()
    ): Int {

        require(url.isNotEmpty() && path.isNotEmpty() && fileName.isNotEmpty()) {
            "Missing ${if (url.isEmpty()) "url" else if (path.isEmpty()) "path" else "fileName"}"
        }

        val downloadRequest = DownloadRequest(
            url = url,
            path = path,
            fileName = fileName,
            tag = tag,
            headers = headers,
            metaData = metaData,
            notificationTitle = notificationTitle,
            notificationParameter = notificationParameter
        )
        downloadManager.downloadAsync(downloadRequest)
        return downloadRequest.id
    }

    /**
     * Cancel download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun cancel(id: Int) {
        downloadManager.cancelAsync(id)
    }

    /**
     * Cancel downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun cancel(tag: String) {
        downloadManager.cancelAsync(tag)
    }

    /**
     * Cancel all the downloads
     *
     */
    fun cancelAll() {
        downloadManager.cancelAllAsync()
    }

    /**
     * Observe all downloads
     *
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloads(): Flow<List<DownloadModel>> {
        return downloadManager.observeAllDownloads()
    }

    /**
     * Observe download with given [id]
     *
     * @param id Unique Download ID of the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadById(id: Int): Flow<DownloadModel> {
        return downloadManager.observeDownloadById(id)
    }

    /**
     * Observe downloads with given [tag]
     *
     * @param tag Tag associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadByTag(tag: String): Flow<List<DownloadModel>> {
        return downloadManager.observeDownloadsByTag(tag)
    }

    /**
     * Pause download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun pause(id: Int) {
        downloadManager.pauseAsync(id)
    }

    /**
     * Pause downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun pause(tag: String) {
        downloadManager.pauseAsync(tag)
    }

    /**
     * Pause all the downloads
     *
     */
    fun pauseAll() {
        downloadManager.pauseAllAsync()
    }

    /**
     * Resume download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun resume(id: Int) {
        downloadManager.resumeAsync(id)
    }

    /**
     * Resume downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun resume(tag: String) {
        downloadManager.resumeAsync(tag)
    }

    /**
     * Resume all the downloads
     *
     */
    fun resumeAll() {
        downloadManager.resumeAllAsync()
    }

    /**
     * Retry download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun retry(id: Int) {
        downloadManager.retryAsync(id)
    }

    /**
     * Retry downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun retry(tag: String) {
        downloadManager.retryAsync(tag)
    }

    /**
     * Retry all the downloads
     *
     */
    fun retryAll() {
        downloadManager.retryAllAsync()
    }

    /**
     * Clear all entries from database and delete all the files
     *
     */
    fun clearAllDb() {
        downloadManager.clearAllDbAsync()
    }

    /**
     * Clear entries from database and delete files on or before [timeInMillis]
     *
     * @param timeInMillis timestamp in millisecond
     */
    fun clearDb(timeInMillis: Long) {
        downloadManager.clearDbAsync(timeInMillis)
    }

    /**
     * Clear entry from database and delete file with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun clearDb(id: Int) {
        downloadManager.clearDbAsync(id)
    }

    /**
     * Clear entries from database and delete files with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun clearDb(tag: String) {
        downloadManager.clearDbAsync(tag)
    }

    /**
     * Suspend function to make headers only api call to get and compare ETag string of content
     *
     * @param url Download Url
     * @param headers Optional headers associated with url of download request
     * @param eTag Existing ETag of content
     * @return Boolean to compare existing and newly fetched ETag of the content
     */
    suspend fun isContentValid(
        url: String,
        headers: HashMap<String, String> = hashMapOf(),
        eTag: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            ApiResponseHeaderChecker(url, RetrofitInstance.getDownloadService(), headers)
                .getHeaderValue(DownloadConst.ETAG_HEADER) == eTag
        }

    /**
     * Suspend function to make headers only api call to get length of content in bytes
     *
     * @param url Download Url
     * @param headers Optional headers associated with url of download request
     * @return Length of content to be downloaded in bytes
     */
    suspend fun getContentLength(
        url: String,
        headers: HashMap<String, String> = hashMapOf()
    ): Long =
        withContext(Dispatchers.IO) {
            ApiResponseHeaderChecker(url, RetrofitInstance.getDownloadService(), headers)
                .getHeaderValue(DownloadConst.CONTENT_LENGTH)?.toLong() ?: 0
        }

    /**
     * Suspend function to get list of all Downloads
     *
     * @return List of [DownloadModel]
     */
    suspend fun getAllDownloads() = downloadManager.getAllDownloads()

}
