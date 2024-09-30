package com.ketch.internal.download

import com.ketch.internal.utils.FileUtil.getUniqueId

internal data class DownloadRequest(
    val url: String,
    val path: String,
    val fileName: String,
    val notificationParameter: String,
    val notificationTitle: String,
    val tag: String,
    val id: Int = getUniqueId(url, path, fileName),
    val headers: HashMap<String, String> = hashMapOf(),
    val metaData: String = ""
)
