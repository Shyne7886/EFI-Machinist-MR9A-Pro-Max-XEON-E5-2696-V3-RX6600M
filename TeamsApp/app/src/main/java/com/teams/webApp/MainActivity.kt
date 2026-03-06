package com.teams.webApp

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TEAMS_URL = "https://teams.microsoft.com"
        private const val PREFS_NAME = "TeamsWebAppPrefs"
        private const val KEY_LAST_URL = "last_url"
        // Desktop user agent so Teams Web loads the full experience
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var offlineLayout: View
    private lateinit var offlineRetryButton: Button

    // File chooser callback for upload dialog
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Permission request launcher for runtime permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled; WebView continues regardless
    }

    // File chooser result launcher
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                if (data.clipData != null) {
                    // Multiple files
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        offlineLayout = findViewById(R.id.offlineLayout)
        offlineRetryButton = findViewById(R.id.retryButton)
        webView = findViewById(R.id.webView)

        requestRuntimePermissions()
        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        setupOfflineRetry()

        // Handle intent deep-links (e.g. tapping a Teams notification)
        val deepLinkUrl = intent?.data?.toString()
        val startUrl = if (!deepLinkUrl.isNullOrEmpty() && deepLinkUrl.startsWith("https://teams.microsoft.com")) {
            deepLinkUrl
        } else {
            savedInstanceState?.getString(KEY_LAST_URL) ?: TEAMS_URL
        }

        if (isNetworkAvailable()) {
            loadUrl(startUrl)
        } else {
            showOfflineView()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("https://teams.microsoft.com") }?.let {
            loadUrl(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.url?.let { outState.putString(KEY_LAST_URL, it) }
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        // JavaScript and DOM storage (required for Teams)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Use desktop user agent so Teams loads the full web app
        settings.userAgentString = DESKTOP_USER_AGENT

        // Zoom and viewport
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Media
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true

        // Cache – use cache when available, fall back to network
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Mixed content (HTTPS page loading HTTPS resources only)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Enable hardware acceleration at View level
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = TeamsWebViewClient()
        webView.webChromeClient = TeamsWebChromeClient()
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(R.color.teams_purple, R.color.teams_blue)
        swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                showOfflineView()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupOfflineRetry() {
        offlineRetryButton.setOnClickListener {
            if (isNetworkAvailable()) {
                showWebView()
                loadUrl(TEAMS_URL)
            } else {
                // Still offline – shake the button (no-op here, just inform user)
            }
        }
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    private fun showOfflineView() {
        offlineLayout.visibility = View.VISIBLE
        swipeRefreshLayout.visibility = View.GONE
    }

    private fun showWebView() {
        offlineLayout.visibility = View.GONE
        swipeRefreshLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun requestRuntimePermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissionsToRequest += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    // -------------------------------------------------------------------------
    // WebViewClient – handles page navigation inside the WebView
    // -------------------------------------------------------------------------
    inner class TeamsWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when {
                // Keep Teams and Microsoft login URLs inside the WebView
                url.startsWith("https://teams.microsoft.com") ||
                url.startsWith("https://login.microsoftonline.com") ||
                url.startsWith("https://login.live.com") ||
                url.startsWith("https://account.microsoft.com") ||
                url.startsWith("https://www.microsoft.com") ||
                url.startsWith("https://statics.teams.cdn.office.net") ||
                url.startsWith("https://teams.cdn.office.net") -> false

                // Open everything else in the system browser
                url.startsWith("http") || url.startsWith("https") -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }

                else -> false
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE
            showWebView()
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefreshLayout.isRefreshing = false

            // Persist current URL so we restore after rotation
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_URL, url).apply()
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (!isNetworkAvailable()) {
                showOfflineView()
            }
        }
    }

    // -------------------------------------------------------------------------
    // WebChromeClient – handles JS dialogs, permissions, file chooser, progress
    // -------------------------------------------------------------------------
    inner class TeamsWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            if (newProgress == 100) {
                progressBar.visibility = View.GONE
            } else {
                progressBar.visibility = View.VISIBLE
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Grant camera and microphone access to the Teams web page
            val granted = request.resources.filter { resource ->
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }
            if (granted.isNotEmpty()) {
                request.grant(granted.toTypedArray())
            } else {
                request.deny()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false) // deny location by default
        }

        // File chooser for file uploads (attachments, images)
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            val intent = fileChooserParams.createIntent()
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            fileChooserLauncher.launch(intent)
            return true
        }

        // JS alert
        override fun onJsAlert(
            view: WebView, url: String, message: String,
            result: android.webkit.JsResult
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        // JS confirm
        override fun onJsConfirm(
            view: WebView, url: String, message: String,
            result: android.webkit.JsResult
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            super.onReceivedTitle(view, title)
            // Keep app title as "Teams" regardless of page title
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
