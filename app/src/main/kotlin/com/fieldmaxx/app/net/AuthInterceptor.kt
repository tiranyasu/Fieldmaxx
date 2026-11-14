package com.fieldmaxx.app.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to requests for the Fieldmaxx server, and only to those: the session token
 * never goes to another host (an APK link elsewhere, OneDrive). OkHttp itself drops it on a redirect to another host.
 */
class AuthInterceptor(private val vault: TokenVault, private val serverUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = vault.load()
        val server = serverUrl().toHttpUrlOrNull()
        if (token == null || server == null || !request.url.sameOrigin(server)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port
}
