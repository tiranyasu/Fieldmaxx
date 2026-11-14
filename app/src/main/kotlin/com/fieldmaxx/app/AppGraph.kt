package com.fieldmaxx.app

import android.content.Context
import com.fieldmaxx.app.net.AndroidKeystoreTokenVault
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.AuthInterceptor
import com.fieldmaxx.app.net.OkHttpApi
import com.fieldmaxx.app.net.ServerAddress
import com.fieldmaxx.app.net.TokenVault
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The app's long-lived objects, built once per process by [FieldmaxxApp]. Tests pass an
 * [com.fieldmaxx.app.net.InMemoryTokenVault], since AndroidKeyStore doesn't exist under Robolectric.
 */
class AppGraph(context: Context, val vault: TokenVault = AndroidKeystoreTokenVault(context)) {
    private val app = context.applicationContext

    val server = ServerAddress(app)

    // Reading a large job file can take the server a while before it answers.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(vault, server::get))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val api: Api = OkHttpApi(http, server::get)
}
