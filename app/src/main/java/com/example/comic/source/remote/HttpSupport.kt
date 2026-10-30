package com.example.comic.source.remote

import com.example.comic.model.SourceConfig
import com.example.comic.source.SourceException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal fun OkHttpClient.execute(request: Request): okhttp3.Response = try {
    newCall(request).execute()
} catch (e: IOException) {
    throw SourceException("网络请求失败：${e.message}", e)
}

internal fun Request.Builder.authorize(config: SourceConfig): Request.Builder {
    val user = config.username?.takeIf { it.isNotBlank() } ?: return this
    return header("Authorization", Credentials.basic(user, config.password.orEmpty()))
}

internal fun normalizeBaseUrl(value: String?): HttpUrl {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) throw SourceException("服务器地址不能为空")
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val parsed = withScheme.trimEnd('/').toHttpUrlOrNull()
        ?: throw SourceException("服务器地址格式不正确")
    return parsed
}

/**
 * Maps a transport-level failure to [AdaptiveBaseUrl.ConnectivityFailure] so
 * sources can fail over to their other address. HTTP error responses (4xx/5xx)
 * are the server speaking — those are not address problems.
 */
internal fun connectivityFailure(base: HttpUrl, e: IOException): AdaptiveBaseUrl.ConnectivityFailure =
    AdaptiveBaseUrl.ConnectivityFailure(base, e)

internal fun HttpUrl.appendPath(path: String): HttpUrl {
    val trimmed = path.trim().trim('/')
    if (trimmed.isEmpty()) return this
    return newBuilder().addPathSegments(trimmed).build()
}
