package com.pattayacctv.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE_URL = "https://livestream.pattaya.go.th/"
        private const val PREFS = "pattaya_cctv_prefs"
        private const val KEY_FAVORITES = "favorite_camera_urls"
        private const val INFO_REFRESH_MS = 10 * 60 * 1000L
    }

    private lateinit var binding: ActivityMainBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFailed = false
    private var focusSearchAfterLoad = false
    private val infoHandler = Handler(Looper.getMainLooper())
    private val infoRefreshRunnable = Runnable { loadLiveInfo() }

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
        setupLiveInfo()
        loadLiveInfo()
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
            userAgentString = "$userAgentString PattayaCCTVViewer/2.1"
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
                view?.evaluateJavascript("(function(){return window.location.href;})();") { jsValue ->
                    updateFavoriteButton(decodeJavascriptString(jsValue) ?: url)
                }
                injectFavoriteTracker()
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
                setFullscreenImmersive(true)
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
        binding.refreshInfoButton.setOnClickListener { loadLiveInfo(true) }
        binding.weatherCard.setOnClickListener { openExternalUrl(LiveInfoRepository.WEATHER_DETAIL_URL) }
        binding.oilCard.setOnClickListener { openExternalUrl(LiveInfoRepository.OIL_DETAIL_URL) }
        binding.goldCard.setOnClickListener { openExternalUrl(LiveInfoRepository.GOLD_DETAIL_URL) }
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

    private fun injectFavoriteTracker() {
        binding.webView.evaluateJavascript(
            """
            (function(){
              if(window.__pattayaFavoriteTrackerInstalled) return 'ready';
              window.__pattayaFavoriteTrackerInstalled = true;
              window.__pattayaLastCameraId = null;

              function findId(text){
                if(!text) return null;
                var m = String(text).match(/\b(?:CC|NC|SC|RC)-\d+\b|\bCAM\s*-?\s*\d+\b/i);
                if(!m) return null;
                return m[0].replace(/\s+/g,'').replace(/^CAM(\d+)$/i,'CAM-$1').toUpperCase();
              }

              function remember(text){
                if(!text || text.length > 500) return;
                var id = findId(text);
                if(id) window.__pattayaLastCameraId = id;
              }

              document.addEventListener('click', function(ev){
                var node = ev.target;
                for(var i=0; node && i<8; i++, node=node.parentElement){
                  var text = (node.innerText || node.textContent || '').trim();
                  if(text && text.length <= 500 && findId(text)){
                    remember(text);
                    break;
                  }
                }

                setTimeout(function(){
                  var nodes = document.querySelectorAll('[aria-selected="true"],[class*="selected"],[class*="active"],[class*="popup"],[class*="modal"]');
                  for(var j=0; j<nodes.length; j++){
                    var t = (nodes[j].innerText || nodes[j].textContent || '').trim();
                    if(t && t.length <= 500 && findId(t)) remember(t);
                  }
                }, 350);
              }, true);

              var initial = findId(decodeURIComponent(window.location.href || ''));
              if(initial) window.__pattayaLastCameraId = initial;
              return 'ready';
            })();
            """.trimIndent(),
            null
        )
    }

    private fun normalizeCameraId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var id = value.trim().uppercase(Locale.US)
        id = id.replace(Regex("""^CAM\s*-?\s*(\d+)$"""), "CAM-$1")
        return if (Regex("""^(?:(?:CC|NC|SC|RC)-\d+|CAM-\d+)$""").matches(id)) id else null
    }

    private fun favorites(): MutableSet<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet()
            ?: mutableSetOf()

    private fun saveFavorites(values: Set<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_FAVORITES, values).apply()
    }

    private fun toggleCurrentFavorite() {
        binding.webView.evaluateJavascript(
            """
            (function(){
              function normalise(value){
                if(!value) return null;
                var m = String(value).match(/\b(?:CC|NC|SC|RC)-\d+\b|\bCAM\s*-?\s*\d+\b/i);
                if(!m) return null;
                return m[0].replace(/\s+/g,'').replace(/^CAM(\d+)$/i,'CAM-$1').toUpperCase();
              }
              var fromUrl = normalise(decodeURIComponent(window.location.href || ''));
              if(fromUrl) return fromUrl;
              if(window.__pattayaLastCameraId) return normalise(window.__pattayaLastCameraId);

              var selectors = [
                '[aria-selected="true"]',
                '[class*="selected"]',
                '[class*="active"]',
                '[class*="popup"]',
                '[class*="modal"]'
              ];
              for(var s=0; s<selectors.length; s++){
                var nodes = document.querySelectorAll(selectors[s]);
                for(var i=0; i<nodes.length; i++){
                  var text = (nodes[i].innerText || nodes[i].textContent || '').trim();
                  if(text && text.length < 500){
                    var id = normalise(text);
                    if(id) return id;
                  }
                }
              }
              return null;
            })();
            """.trimIndent()
        ) { jsValue ->
            val selectedId = normalizeCameraId(decodeJavascriptString(jsValue))
                ?: extractCameraId(binding.webView.url)

            if (selectedId.isNullOrBlank()) {
                Toast.makeText(this, R.string.favorite_choose_camera, Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }

            val canonicalUrl = cameraUrl(selectedId)
            val set = favorites()
            val added = if (set.any { extractCameraId(it).equals(selectedId, ignoreCase = true) }) {
                set.removeAll { extractCameraId(it).equals(selectedId, ignoreCase = true) }
                false
            } else {
                set.add(canonicalUrl)
                true
            }

            saveFavorites(set)
            updateFavoriteButton(canonicalUrl)
            Toast.makeText(
                this,
                if (added) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateFavoriteButton(url: String?) {
        val cameraId = extractCameraId(url)
        val isFavorite = cameraId != null &&
            favorites().any { extractCameraId(it).equals(cameraId, ignoreCase = true) }

        binding.favoriteCurrentButton.text = if (isFavorite) "★" else "☆"
        binding.favoriteCurrentButton.contentDescription = getString(
            if (isFavorite) R.string.remove_favorite else R.string.add_favorite
        )
    }

    private fun extractCameraId(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null

        // Decode more than once because liff.state can itself contain an encoded route.
        var decoded: String = rawUrl
        repeat(3) {
            val next = Uri.decode(decoded)
            if (next == decoded) return@repeat
            decoded = next
        }

        Regex("""/live-cctv/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalizeCameraId(it) }

        return runCatching {
            val uri = Uri.parse(rawUrl)
            val state = uri.getQueryParameter("liff.state")
            state?.let {
                Regex("""/live-cctv/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                    .find(Uri.decode(it))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { normalizeCameraId(it) }
            }
        }.getOrNull()
    }

    private fun cameraUrl(cameraId: String): String {
        val state = "/live-cctv/$cameraId"
        return Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("liff.state", state)
            .build()
            .toString()
    }

    private fun decodeJavascriptString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return runCatching {
            JSONObject("{\"value\":$value}").getString("value")
        }.getOrNull()
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
        val id = extractCameraId(url)
        return if (!id.isNullOrBlank()) "กล้อง $id"
        else url.removePrefix(BASE_URL).ifBlank { getString(R.string.viewer_title) }
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

    private fun setupLiveInfo() {
        binding.weatherSource.text = "Open-Meteo • แตะดูพยากรณ์จากกรมอุตุนิยมวิทยา"
        binding.oilSource.text = "กระทรวงพลังงาน • แตะดูข้อมูลต้นทาง"
        binding.goldSource.text = "สมาคมค้าทองคำ • แตะดูข้อมูลต้นทาง"
    }

    private fun loadLiveInfo(showLoading: Boolean = false) {
        infoHandler.removeCallbacks(infoRefreshRunnable)
        infoHandler.postDelayed(infoRefreshRunnable, INFO_REFRESH_MS)

        if (showLoading) {
            binding.weatherValue.setText(R.string.info_loading)
            binding.oilValue.setText(R.string.info_loading)
            binding.goldValue.setText(R.string.info_loading)
        }
        binding.liveInfoUpdated.text = "กำลังอัปเดตข้อมูลล่าสุด..."

        Thread {
            val data = runCatching { LiveInfoRepository.fetchWeather() }.getOrNull()
            runOnUiThread {
                binding.weatherValue.text = data?.summary ?: getString(R.string.info_unavailable)
                updateInfoTimestamp()
            }
        }.start()

        Thread {
            val data = runCatching { LiveInfoRepository.fetchOil() }.getOrNull()
            runOnUiThread {
                binding.oilValue.text = data?.summary ?: getString(R.string.info_unavailable)
                updateInfoTimestamp()
            }
        }.start()

        Thread {
            val data = runCatching { LiveInfoRepository.fetchGold() }.getOrNull()
            runOnUiThread {
                binding.goldValue.text = data?.summary ?: getString(R.string.info_unavailable)
                updateInfoTimestamp()
            }
        }.start()
    }

    private fun updateInfoTimestamp() {
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("th", "TH")).format(Date())
        binding.liveInfoUpdated.text = "อัปเดตล่าสุด: $time • อัตโนมัติทุก 10 นาที"
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.load_error_message, Toast.LENGTH_SHORT).show()
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
        setFullscreenImmersive(false)
    }

    private fun setFullscreenImmersive(enabled: Boolean) {
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
        infoHandler.removeCallbacks(infoRefreshRunnable)
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
