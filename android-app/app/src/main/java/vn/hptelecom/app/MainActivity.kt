package vn.hptelecom.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeoOrigin: String? = null
    private var cameraUri: Uri? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private val baseHost: String = Uri.parse(BuildConfig.BASE_URL).host ?: ""

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(webView)
            setColorSchemeResources(R.color.accent)
            setProgressBackgroundColorSchemeResource(R.color.canvas)
            setOnRefreshListener { webView.reload() }
        }
        setContentView(refresh)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
            userAgentString = "$userAgentString HPTelecomApp/1.0"
        }
        webView.addJavascriptInterface(BlobBridge(), "HPTelecomApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == baseHost) return false
                if (uri.scheme == "blob") return false
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                refresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                refresh.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    refresh.isRefreshing = false
                    view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "utf-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                cameraUri = null
                val wantsImage = params.acceptTypes.isEmpty() || params.acceptTypes.any { it.isBlank() || it.startsWith("image") }
                return try {
                    val pick = params.createIntent()
                    val chooser = Intent(Intent.ACTION_CHOOSER).putExtra(Intent.EXTRA_INTENT, pick)
                        .putExtra(Intent.EXTRA_TITLE, "Chọn ảnh hoặc chụp mới")
                    if (wantsImage) {
                        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                        val file = java.io.File(dir, "anh-${System.currentTimeMillis()}.jpg")
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                        cameraUri = uri
                        val shoot = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shoot))
                    }
                    startActivityForResult(chooser, REQUEST_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQUEST_LOCATION
                    )
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.deny() }
            }
        }

        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (url.startsWith("blob:")) {
                downloadBlob(url, mime, URLUtil.guessFileName(url, disposition, mime))
            } else {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    addRequestHeader("User-Agent", userAgent)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalFilesDir(
                        this@MainActivity,
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, disposition, mime)
                    )
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Đang tải xuống…", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(intent?.data?.toString()?.takeIf { it.startsWith(BuildConfig.BASE_URL) } ?: BuildConfig.BASE_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            val taken = cameraUri?.takeIf { resultCode == RESULT_OK && (data == null || (data.data == null && data.clipData == null)) }
            val uris = when {
                taken != null -> arrayOf(taken)
                resultCode == RESULT_OK -> android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                else -> null
            }
            cameraUri = null
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun offlineHtml(): String {
        val retry = if (isOnline()) "Máy chủ chưa phản hồi." else getString(R.string.offline_hint)
        return """<html><body style="margin:0;display:grid;place-items:center;height:100vh;background:#1e2a38;color:#f3f6fa;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box">
            <div><h2>${getString(R.string.offline_title)}</h2><p style="color:#b8c5d3">$retry</p>
            <button onclick="location.href='${BuildConfig.BASE_URL}'" style="padding:12px 20px;border:0;border-radius:8px;background:#5cc7d1;color:#102a3e;font-weight:700">Thử lại</button></div></body></html>"""
    }

    private fun downloadBlob(url: String, mime: String?, name: String) {
        val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (async()=>{try{const b=await (await fetch('$url')).blob();const r=new FileReader();
            r.onloadend=()=>HPTelecomApp.save(r.result.split(',')[1],'${mime ?: "application/octet-stream"}','$safeName');r.readAsDataURL(b)}catch(e){HPTelecomApp.fail()}})()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun save(base64: String, mime: String, name: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException()
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    java.io.File(dir, safe).writeBytes(bytes)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Đã lưu $safe vào Tải xuống", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                fail()
            }
        }

        @JavascriptInterface
        fun fail() {
            runOnUiThread { Toast.makeText(this@MainActivity, "Không thể lưu tệp", Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        private const val REQUEST_FILE = 1001
        private const val REQUEST_LOCATION = 1002
    }
}
