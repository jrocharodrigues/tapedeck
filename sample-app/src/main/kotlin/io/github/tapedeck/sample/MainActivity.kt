package io.github.tapedeck.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var textView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this).apply {
            text = "Loading..."
            textSize = 20f
            setPadding(32, 32, 32, 32)
        }
        setContentView(textView)

        fetchData()
    }

    private fun fetchData() {
        val request = Request.Builder()
            .url("https://api.github.com/zen")
            .header("User-Agent", "Tapedeck-Sample")
            .build()

        AppNetwork.okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    textView.text = "Error: ${e.message}"
                    reportFullyDrawn()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() } ?: "Empty"
                mainHandler.post {
                    textView.text = body
                    reportFullyDrawn()
                }
            }
        })
    }
}
