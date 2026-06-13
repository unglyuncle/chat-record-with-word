package com.overmind.meetingscribe.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp clients for long-lived WebSockets and bounded-time model downloads. */
object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived sockets
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
