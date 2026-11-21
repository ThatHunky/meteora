package dev.thathunky.meteora.ui.signin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.MLinearLoading
import dev.thathunky.meteora.ytm.YtmAuthStore
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SignInViewModel @Inject constructor(val auth: YtmAuthStore) : ViewModel()

/**
 * Google sign-in inside a WebView (PLAN.md Q4, spike S2). Google may refuse embedded
 * WebViews; we present a plain mobile Chrome user agent (no "; wv" token). When the WebView
 * lands on music.youtube.com with a session cookie, the cookie header is stored encrypted.
 * The user types their own credentials; the app never sees them, only the resulting cookies.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(onDone: () -> Unit, vm: SignInViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.signin_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
        )
        Box(Modifier.fillMaxWidth()) {
            if (loading) MLinearLoading(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            // Compare the host: a prefix check would also accept music.youtube.com.example.
                            val uri = url?.let(Uri::parse)
                            if (finished || uri?.scheme != "https" || uri.host != "music.youtube.com") return
                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                            if (!vm.auth.isSession(cookies)) return
                            finished = true
                            CookieManager.getInstance().flush()
                            scope.launch {
                                vm.auth.save(cookies)
                                onDone()
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
