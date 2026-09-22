package com.mediasaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Transparent activity that receives shared URLs from other apps.
 * It extracts the URL and forwards it to MainActivity.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val url = extractUrlFromIntent(intent)
        
        if (!url.isNullOrBlank()) {
            // Forward to MainActivity
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("SHARED_URL", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(mainIntent)
        }
        
        // Always dismiss this transparent activity
        finish()
    }

    private fun extractUrlFromIntent(intent: Intent): String? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                    extractUrlFromText(text)
                } else null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type == "text/plain") {
                    // Just take the first valid URL found in the shared texts
                    val texts = intent.getStringArrayListExtra(Intent.EXTRA_TEXT) ?: return null
                    for (text in texts) {
                        val url = extractUrlFromText(text)
                        if (url != null) return url
                    }
                    null
                } else null
            }
            else -> null
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value
    }
}
