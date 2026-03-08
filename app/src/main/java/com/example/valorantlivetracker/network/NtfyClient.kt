package com.example.valorantlivetracker.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NtfyClient {
    private val client = OkHttpClient()
    private val ntfyUrl = "https://ntfy.sh/vlr_live_v1"

    fun sendNotification(message: String, isMatchPoint: Boolean = false) {
        val request = Request.Builder()
            .url(ntfyUrl)
            .addHeader("X-ID", "vlr_live_v1")
            .addHeader("X-Tag", "vlr_score")
            .addHeader("X-Ongoing", "yes")
            .addHeader("Content-Type", "text/plain")
            .apply {
                if (isMatchPoint) {
                    addHeader("X-Priority", "5")
                    addHeader("X-Vibration", "1000,500,1000")
                }
            }
            .post(message.toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
