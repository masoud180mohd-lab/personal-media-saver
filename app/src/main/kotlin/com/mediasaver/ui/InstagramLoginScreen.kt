package com.mediasaver.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mediasaver.platform.instagram.InstagramSessionManager

private const val TAG = "InstagramLoginScreen"
private const val IG_LOGIN_URL = "https://www.instagram.com/accounts/login/"
private const val IG_BASE_URL = "https://www.instagram.com"

/**
 * Full-screen WebView login screen for Instagram.
 * Once the user successfully logs in, the session cookies are captured
 * and stored locally via [InstagramSessionManager]. No cookies or credentials
 * are transmitted to any external server — strictly on-device only.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramLoginScreen(
    sessionManager: InstagramSessionManager,
    onLoginSuccess: () -> Unit,
    onLoginCancelled: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Progress bar at top
        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Enable JavaScript and DOM storage for Instagram's React app
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; SM-A165F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                        // Enable third-party cookies for Instagram
                        val webViewInstance = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webViewInstance, true)
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress.toFloat()
                                isLoading = newProgress < 100
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                Log.d(TAG, "Page started: ${url?.take(80)}")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                Log.d(TAG, "Page finished: ${url?.take(80)}")

                                // Check if user has sessionid cookie regardless of specific URL format
                                val cookies = CookieManager.getInstance().getCookie(IG_BASE_URL)
                                if (!cookies.isNullOrBlank() && cookies.contains("sessionid=")) {
                                    Log.d(TAG, "Login detected via sessionid cookie! Saving session.")
                                    sessionManager.saveCookies(cookies)
                                    onLoginSuccess()
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Keep navigation within Instagram domain
                                return if (url.startsWith(IG_BASE_URL) ||
                                    url.contains("instagram.com") ||
                                    url.contains("facebook.com/login") ||
                                    url.contains("accounts.google.com")
                                ) {
                                    false // Let the WebView handle it
                                } else {
                                    true // Block external URLs
                                }
                            }
                        }

                        loadUrl(IG_LOGIN_URL)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading && progress < 10f) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading Instagram...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Bottom Action Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "When finished logging in:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val cookies = CookieManager.getInstance().getCookie(IG_BASE_URL)
                        if (!cookies.isNullOrBlank() && cookies.contains("sessionid=")) {
                            Log.d(TAG, "Manual save: sessionid found")
                            sessionManager.saveCookies(cookies)
                            onLoginSuccess()
                        } else {
                            Log.w(TAG, "Manual save: sessionid not found in cookies")
                            onLoginCancelled()
                        }
                    },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Done", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}
