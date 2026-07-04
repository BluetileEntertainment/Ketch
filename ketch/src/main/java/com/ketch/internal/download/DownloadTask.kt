package com.ketch.internal.download

import com.ketch.internal.network.DownloadService
import com.ketch.internal.utils.DownloadConst
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import retrofit2.Response
import okhttp3.ResponseBody

internal class DownloadTask(
    private var url: String,
    private var path: String,
    private var fileName: String,
    private val downloadService: DownloadService,
) {

    companion object {
        private const val VALUE_200 = 200
        private const val VALUE_299 = 299
        private const val DEFAULT_PROGRESS_INTERVAL_MS = 5000L
        private const val IO_BUFFER_SIZE = 2 * 1024 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val CONTENT_RANGE_HEADER = "Content-Range"
    }

    suspend fun download(
        headers: MutableMap<String, String> = mutableMapOf(),
        progressIntervalMsProvider: (Long) -> Long = { DEFAULT_PROGRESS_INTERVAL_MS },
        onStart: suspend (Long) -> Unit,
        onProgress: suspend (Long, Long, Float) -> Unit
    ): Long {

        var rangeStart = 0L
        val file = File(path, fileName)

        if (file.exists()) {
            rangeStart = file.length()
        }

        if (rangeStart != 0L) {
            headers[DownloadConst.RANGE_HEADER] = "bytes=$rangeStart-"
        }

        var response = downloadService.getUrl(url, headers)
        if (rangeStart > 0L) {
            when {
                response.code() == HTTP_PARTIAL_CONTENT -> Unit
                response.code() == DownloadConst.HTTP_RANGE_NOT_SATISFY -> {
                    val knownTotalBytes = response.contentRangeTotalBytes()
                    if (knownTotalBytes != null && knownTotalBytes == rangeStart) {
                        onStart.invoke(rangeStart)
                        onProgress.invoke(rangeStart, rangeStart, 0F)
                        return rangeStart
                    }
                    throw IOException(
                        "Server rejected resume range at $rangeStart bytes; partial file preserved"
                    )
                }
                response.code() == VALUE_200 -> {
                    throw IOException(
                        "Server ignored resume range at $rangeStart bytes; partial file preserved"
                    )
                }
            }
        }

        val responseBody = response.body()

        if (response.code() !in VALUE_200..VALUE_299 ||
            responseBody == null
        ) {
            throw IOException(
                "Something went wrong, response code: ${response.code()}, responseBody null: ${responseBody == null}"
            )
        }

        var totalBytes = responseBody.contentLength()

        if (totalBytes < 0) throw IOException("Content Length is wrong: $totalBytes")

        var progressBytes = 0L

        totalBytes += rangeStart

        val progressIntervalMs = maxOf(1L, progressIntervalMsProvider(totalBytes))

        val out = FileOutputStream(file, true)

        responseBody.byteStream().use { inputStream ->
            BufferedOutputStream(out, IO_BUFFER_SIZE).use { outputStream ->

                if (rangeStart != 0L) {
                    progressBytes = rangeStart
                }

                onStart.invoke(totalBytes)

                val buffer = ByteArray(IO_BUFFER_SIZE)
                var bytes = inputStream.read(buffer)
                var tempBytes = 0L
                var progressInvokeTime = System.currentTimeMillis()
                var speed: Float

                while (bytes >= 0) {

                    outputStream.write(buffer, 0, bytes)
                    progressBytes += bytes
                    tempBytes += bytes
                    bytes = inputStream.read(buffer)
                    val finalTime = System.currentTimeMillis()
                    if (finalTime - progressInvokeTime >= progressIntervalMs) {

                        val timeDelta = maxOf(1L, finalTime - progressInvokeTime)
                        speed = tempBytes.toFloat() / timeDelta.toFloat()
                        tempBytes = 0L
                        progressInvokeTime = System.currentTimeMillis()
                        if (progressBytes > totalBytes) progressBytes = totalBytes
                        onProgress.invoke(
                            progressBytes,
                            totalBytes,
                            speed
                        )
                    }
                }
                onProgress.invoke(totalBytes, totalBytes, 0F)
            }
        }

        return totalBytes
    }
    private fun Response<ResponseBody>.contentRangeTotalBytes(): Long? {
        val contentRange = headers()[CONTENT_RANGE_HEADER] ?: return null
        val total = contentRange.substringAfter("/", missingDelimiterValue = "")
        return total.takeIf { it.isNotBlank() && it != "*" }?.toLongOrNull()
    }
}
