package com.ketch.internal.network

import com.ketch.internal.utils.DownloadConst
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

internal object RetrofitInstance {

    @Volatile
    private var downloadService: DownloadService? = null

    fun getDownloadService(
        connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
        readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS
    ): DownloadService {
        if (downloadService == null) {
            synchronized(this) {
                if (downloadService == null) {
                    val dispatcher = Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 16
                    }
                    downloadService = Retrofit
                        .Builder()
                        .baseUrl(DownloadConst.BASE_URL)
                        .client(
                            OkHttpClient
                                .Builder()
                                .connectTimeout(connectTimeOutInMs, TimeUnit.MILLISECONDS)
                                .readTimeout(readTimeOutInMs, TimeUnit.MILLISECONDS)
                                .dispatcher(dispatcher)
                                .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                                .build()
                        )
                        .build()
                        .create(DownloadService::class.java)
                }
            }
        }
        return downloadService!!
    }
}
