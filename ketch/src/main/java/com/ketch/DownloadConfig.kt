package com.ketch

import com.ketch.internal.utils.DownloadConst

data class DownloadConfig(
    val connectTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_CONNECT_TIMEOUT_MS,
    val readTimeOutInMs: Long = DownloadConst.DEFAULT_VALUE_READ_TIMEOUT_MS,
    val progressIntervalSmallMs: Long = 500L,
    val progressIntervalMediumMs: Long = 1000L,
    val progressIntervalLargeMs: Long = 3000L,
    val progressIntervalXLargeMs: Long = 5000L,
    val minProgressBytesSmall: Long = 256 * 1024L,
    val minProgressBytesMedium: Long = 1 * 1024 * 1024L,
    val minProgressBytesLarge: Long = 5 * 1024 * 1024L,
    val minProgressBytesXLarge: Long = 10 * 1024 * 1024L,
    val thresholdSmallBytes: Long = 20L * 1024 * 1024L,
    val thresholdMediumBytes: Long = 200L * 1024 * 1024L,
    val thresholdLargeBytes: Long = 2L * 1024 * 1024 * 1024L,
)
