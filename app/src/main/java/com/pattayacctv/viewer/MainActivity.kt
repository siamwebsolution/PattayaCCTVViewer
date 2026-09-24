package com.pattayacctv.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pattayacctv.viewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE_URL = "https://livestream.pattaya.go.th/"
        private const val PREFS = "pattaya_cctv_prefs"
        private const val KEY_FAVORITES = "favorite_camera_urls"
    }

    private lateinit var binding: ActivityMainBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFailed = false
    private var focusSearchAfterLoad = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setupBranding()
        setupThemeButton()
        setupWebView()
        setupActions()
        setupBackNavigation()
        showHome()
    }

    private fun setupBranding() {
        binding.homeLogo.setImageResource(R.drawable.logo_pattaya_city)
        binding.homeMayorPhoto.setImageResource(R.drawable.pattaya_mayor)
    }

    private fun setupThemeButton() {
        updateThemeButtonText()
        binding.themeButton.setOnClickListener {
            ThemeHelper.cycleMode(this)
        }
    }

    private fun updateThemeButtonText() {
        binding.themeButton.text = when (ThemeHelper.getMode(this)) {
            ThemeHelper.MODE_LIGHT -> getString(R.string.theme_light)
            ThemeHelper.MODE_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString PattayaCCTVViewer/2.0"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webView.settings.safeBrowsingEnabled = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                return handleUrl(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return url?.let { handleUrl(Uri.parse(it)) } ?: false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageFailed = false
                binding.errorPanel.visibility = View.GONE
                binding.pageProgress.visibility = View.VISIBLE
                updateFavoriteButton(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (!pageFailed) binding.errorPanel.visibility = View.GONE
                binding.viewerTitle.text = view?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.viewer_title)
                updateFavoriteButton(url)
                if (focusSearchAfterLoad) {
                    focusSearchAfterLoad = false
                    focusCameraSearch()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageFailed = true
                    binding.swipeRefresh.isRefreshing = false
                    binding.errorPanel.visibility = View.VISIBLE
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback
                binding.viewerPanel.visibility = View.GONE
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                setImmersive(true)
            }

            override fun onHideCustomView() = hideCustomView()
        }

        binding.swipeRefresh.setOnRefreshListener {
            pageFailed = false
            binding.errorPanel.visibility = View.GONE
            binding.webView.reload()
        }
    }

    private fun setupActions() {
        binding.liveButton.setOnClickListener { openViewer(BASE_URL, false) }
        binding.searchButton.setOnClickListener { openViewer(BASE_URL, true) }
        binding.favoritesButton.setOnClickListener { showFavorites() }

        binding.backHomeButton.setOnClickListener { showHome() }
        binding.favoritesBackButton.setOnClickListener { showHome() }

        binding.refreshButton.setOnClickListener { binding.webView.reload() }
        binding.openBrowserButton.setOnClickListener {
            val url = binding.webView.url ?: BASE_URL
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        binding.retryButton.setOnClickListener {
            pageFailed = false
            binding.errorPanel.visibility = View.GONE
            binding.webView.loadUrl(binding.webView.url ?: BASE_URL)
        }
        binding.favoriteCurrentButton.setOnClickListener { toggleCurrentFavorite() }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.viewerPanel.visibility == View.VISIBLE && binding.webView.canGoBack() -> binding.webView.goBack()
                    binding.viewerPanel.visibility == View.VISIBLE -> showHome()
                    binding.favoritesPanel.visibility == View.VISIBLE -> showHome()
                    else -> finish()
                }
            }
        })
    }

    private fun openViewer(url: String, focusSearch: Boolean) {
        binding.homePanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.VISIBLE
        focusSearchAfterLoad = focusSearch
        if (binding.webView.url == url && !focusSearch) {
            binding.webView.reload()
        } else {
            binding.webView.loadUrl(url)
        }
        if (focusSearch && binding.webView.url == url) {
            binding.webView.reload()
        }
    }

    private fun showHome() {
        binding.viewerPanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.homePanel.visibility = View.VISIBLE
        updateThemeButtonText()
    }

    private fun showFavorites() {
        renderFavorites()
        binding.homePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun focusCameraSearch() {
        binding.webView.evaluateJavascript(
            """
            (function(){
              var tries = 0;
              var timer = setInterval(function(){
                var el = document.querySelector('input[placeholder*="Search camera"], input[type="search"]');
                if(el){
                  clearInterval(timer);
                  el.scrollIntoView({behavior:'smooth',block:'center'});
                  el.focus();
                  el.click();
                }
                if(++tries > 12) clearInterval(timer);
              }, 300);
              return 'search-ready';
            })();
            """.trimIndent(),
            null
        )
        Toast.makeText(this, getString(R.string.search_hint), Toast.LENGTH_LONG).show()
    }

    private fun favorites(): MutableSet<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet()
            ?: mutableSetOf()

    private fun saveFavorites(values: Set<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_FAVORITES, values).apply()
    }

    private fun toggleCurrentFavorite() {
        val url = binding.webView.url ?: return
        if (!url.startsWith("https://livestream.pattaya.go.th")) return
        if (!url.contains("/live-cctv/")) {
            Toast.makeText(this, R.string.favorite_choose_camera, Toast.LENGTH_SHORT).show()
            return
        }
        val set = favorites()
        val added = if (set.contains(url)) {
            set.remove(url)
            false
        } else {
            set.add(url)
            true
        }
        saveFavorites(set)
        updateFavoriteButton(url)
        Toast.makeText(
            this,
            if (added) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteButton(url: String?) {
        val isFavorite = url != null && favorites().contains(url)
        binding.favoriteCurrentButton.text = if (isFavorite) "★" else "☆"
        binding.favoriteCurrentButton.contentDescription = getString(
            if (isFavorite) R.string.remove_favorite else R.string.add_favorite
        )
    }

    private fun renderFavorites() {
        val set = favorites().toList().sorted()
        binding.favoritesList.removeAllViews()
        binding.favoritesEmpty.visibility = if (set.isEmpty()) View.VISIBLE else View.GONE

        set.forEach { url ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = dp(1).toFloat()
                strokeWidth = dp(1)
                setStrokeColor(getColor(R.color.pattaya_border))
                setCardBackgroundColor(getColor(R.color.pattaya_surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
            }

            val label = TextView(this).apply {
                text = cameraLabel(url)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.pattaya_text))
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openViewer(url, false) }
            }

            val open = MaterialButton(this).apply {
                text = getString(R.string.open)
                isAllCaps = false
                setOnClickListener { openViewer(url, false) }
            }

            val remove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "×"
                textSize = 20f
                minimumWidth = dp(48)
                setOnClickListener {
                    val current = favorites()
                    current.remove(url)
                    saveFavorites(current)
                    renderFavorites()
                }
            }

            row.addView(label)
            row.addView(open)
            row.addView(remove)
            card.addView(row)
            binding.favoritesList.addView(card)
        }
    }

    private fun cameraLabel(url: String): String {
        val id = Regex("/live-cctv/([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        return if (!id.isNullOrBlank()) "กล้อง $id" else url.removePrefix(BASE_URL).ifBlank { getString(R.string.viewer_title) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme == "https" || scheme == "http") && host != null && host.endsWith("pattaya.go.th")) {
            return false
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.viewerPanel.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        setImmersive(false)
    }

    private fun setImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
