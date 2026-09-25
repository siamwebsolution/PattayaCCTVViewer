package com.pattayacctv.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
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
import android.widget.CalendarView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pattayacctv.viewer.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE_URL = "https://livestream.pattaya.go.th/"
        private const val PREFS = "pattaya_cctv_prefs"
        private const val KEY_FAVORITES = "favorite_camera_urls"
        private const val KEY_RECENTS = "recent_camera_ids"
        private const val KEY_WEATHER_CACHE = "weather_cache"
        private const val KEY_OIL_CACHE = "oil_cache"
        private const val KEY_GOLD_CACHE = "gold_cache"
        private const val INFO_REFRESH_MS = 10 * 60 * 1000L
    }

    data class RecentCamera(val id: String, val viewedAt: Long)
    data class CameraInfo(val id: String, val label: String?)

    private lateinit var binding: ActivityMainBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFailed = false
    private var focusSearchAfterLoad = false
    private var pendingFavoriteCameraId: String? = null
    private val infoHandler = Handler(Looper.getMainLooper())
    private val infoRefreshRunnable = Runnable { loadLiveInfo() }
    private val cameraTrackerHandler = Handler(Looper.getMainLooper())
    private var lastTrackedCameraId: String? = null
    private var lastTrackedAt: Long = 0L
    private var loadedEvents: List<PattayaEventsRepository.PattayaEvent> = emptyList()
    private var selectedEventCategory: String? = null
    private var selectedEventDateMillis: Long? = null
    private val cameraTrackerRunnable = object : Runnable {
        override fun run() {
            if (::binding.isInitialized && binding.viewerPanel.visibility == View.VISIBLE) {
                detectCurrentCameraForRecent()
            }
            cameraTrackerHandler.postDelayed(this, 1800L)
        }
    }

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
        setupBottomNavigation()
        setupBackNavigation()
        setupLiveInfo()
        cameraTrackerHandler.post(cameraTrackerRunnable)

        renderDashboardFavorites()
        renderDashboardRecents()
        loadCachedInfo()
        loadLiveInfo()
        showHome()
    }

    private fun setupBranding() {
        binding.homeLogo.setImageResource(R.drawable.logo_pattaya_city)
        binding.homeMayorPhoto.setImageResource(R.drawable.pattaya_mayor)
    }

    private fun setupThemeButton() {
        updateThemeButtonText()
        binding.themeButton.setOnClickListener { ThemeHelper.cycleMode(this) }
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
            userAgentString = "$userAgentString PattayaCCTVViewer/2.2"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webView.settings.safeBrowsingEnabled = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrl(uri)
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
                binding.viewerTitle.text =
                    view?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.viewer_title)

                extractCameraId(url)?.let { trackRecentCamera(it) }
                updateFavoriteButton(url)

                view?.evaluateJavascript("(function(){return window.location.href;})();") { jsValue ->
                    val actualUrl = decodeJavascriptString(jsValue) ?: url
                    extractCameraId(actualUrl)?.let { trackRecentCamera(it) }
                    updateFavoriteButton(actualUrl)
                }

                injectFavoriteTracker()

                if (pendingFavoriteCameraId != null) {
                    openPendingFavoriteCamera()
                }

                if (focusSearchAfterLoad) {
                    focusSearchAfterLoad = false
                    focusCameraSearch()
                }

                renderDashboardRecents()
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
                binding.pageProgress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback
                binding.mainShell.visibility = View.GONE
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
        binding.liveButton.setOnClickListener {
            binding.bottomNavigation.menu.findItem(R.id.nav_live).isChecked = true
            openViewer(BASE_URL, false)
        }
        binding.searchButton.setOnClickListener {
            binding.bottomNavigation.menu.findItem(R.id.nav_live).isChecked = true
            openViewer(BASE_URL, true)
        }
        binding.favoritesButton.setOnClickListener { showFavorites() }
        binding.recentButton.setOnClickListener { showRecentDialog() }
        binding.mapButton.setOnClickListener { openMapView() }
        binding.alertsButton.setOnClickListener { showAlertsInfo() }
        binding.contactsButton.setOnClickListener { showImportantContacts() }
        binding.eventsButton.setOnClickListener { showEvents() }
        binding.eventsBackButton.setOnClickListener { showHome() }
        binding.eventsRefreshButton.setOnClickListener { loadEvents(true) }
        binding.eventsCalendar.setOnDateChangeListener { _: CalendarView, year: Int, month: Int, dayOfMonth: Int ->
            val calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedEventDateMillis = calendar.timeInMillis
            binding.eventsDateFilterText.text =
                "📅 วันที่เลือก: " + SimpleDateFormat("dd/MM/yyyy", Locale("th", "TH")).format(calendar.time)
            binding.eventsClearDateButton.visibility = View.VISIBLE
            renderFilteredEvents()
        }
        binding.eventsClearDateButton.setOnClickListener {
            selectedEventDateMillis = null
            binding.eventsDateFilterText.text = "📅 แสดงทุกวัน • แตะวันที่ในปฏิทินเพื่อกรองกิจกรรม"
            binding.eventsClearDateButton.visibility = View.GONE
            renderFilteredEvents()
        }

        binding.situationTraffic.setOnClickListener { openViewer(BASE_URL, true) }
        binding.situationFlood.setOnClickListener { openMapView() }
        binding.situationTravel.setOnClickListener { openMapView() }

        binding.dashboardFavoritesAll.setOnClickListener { showFavorites() }

        binding.dashboardRefreshButton.setOnClickListener {
            loadLiveInfo(true)
            binding.webView.clearCache(false)
            Toast.makeText(this, "กำลังอัปเดตข้อมูลสด", Toast.LENGTH_SHORT).show()
        }

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

        binding.moreAboutButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Pattaya CCTV")
                .setMessage(getString(R.string.more_about_text))
                .setPositiveButton("ตกลง", null)
                .show()
        }
        binding.moreSourceButton.setOnClickListener { openExternalUrl(BASE_URL) }
        binding.moreHistoryButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("การขอภาพ CCTV ย้อนหลัง")
                .setMessage(getString(R.string.more_history_text))
                .setPositiveButton("ตกลง", null)
                .show()
        }
        binding.moreThemeButton.setOnClickListener { ThemeHelper.cycleMode(this) }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showHome()
                R.id.nav_live -> openViewer(BASE_URL, false)
                R.id.nav_map -> openMapView()
                R.id.nav_favorite -> showFavorites()
                R.id.nav_more -> showMore()
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.viewerPanel.visibility == View.VISIBLE && binding.webView.canGoBack() ->
                        binding.webView.goBack()
                    binding.viewerPanel.visibility == View.VISIBLE -> showHome()
                    binding.favoritesPanel.visibility == View.VISIBLE -> showHome()
                    binding.eventsPanel.visibility == View.VISIBLE -> showHome()
                    binding.morePanel.visibility == View.VISIBLE -> showHome()
                    else -> finish()
                }
            }
        })
    }

    private fun openViewer(url: String, focusSearch: Boolean) {
        binding.homePanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.eventsPanel.visibility = View.GONE
        binding.morePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.VISIBLE
        focusSearchAfterLoad = focusSearch
        lastTrackedCameraId = null
        lastTrackedAt = 0L

        if (binding.webView.url == url) {
            binding.webView.reload()
        } else {
            binding.webView.loadUrl(url)
        }
    }

    private fun openMapView() {
        binding.bottomNavigation.menu.findItem(R.id.nav_map).isChecked = true
        openViewer(BASE_URL, false)
        Toast.makeText(this, "แตะหมุดกล้องบนแผนที่เพื่อดูภาพสด", Toast.LENGTH_LONG).show()
    }

    private fun showHome() {
        binding.viewerPanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.eventsPanel.visibility = View.GONE
        binding.morePanel.visibility = View.GONE
        binding.homePanel.visibility = View.VISIBLE

        renderDashboardFavorites()
        renderDashboardRecents()
        updateThemeButtonText()

        binding.bottomNavigation.menu.findItem(R.id.nav_home).isChecked = true
    }

    private fun showFavorites() {
        renderFavorites()
        binding.homePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.GONE
        binding.eventsPanel.visibility = View.GONE
        binding.morePanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.VISIBLE

        binding.bottomNavigation.menu.findItem(R.id.nav_favorite).isChecked = true
    }

    private fun showMore() {
        binding.homePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.eventsPanel.visibility = View.GONE
        binding.morePanel.visibility = View.VISIBLE

        binding.bottomNavigation.menu.findItem(R.id.nav_more).isChecked = true
    }

    private fun showEvents() {
        binding.homePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.morePanel.visibility = View.GONE
        binding.eventsPanel.visibility = View.VISIBLE

        binding.bottomNavigation.menu.findItem(R.id.nav_home).isChecked = true
        loadEvents(false)
    }

    private fun loadEvents(forceRefresh: Boolean) {
        if (!forceRefresh && loadedEvents.isNotEmpty()) {
            renderEventCategoryFilters()
            renderFilteredEvents()
            return
        }

        binding.eventsProgress.visibility = View.VISIBLE
        binding.eventsEmpty.visibility = View.GONE
        binding.eventsRefreshButton.isEnabled = false

        if (forceRefresh) {
            binding.eventsList.removeAllViews()
            binding.eventsUpdated.text = "กำลังอัปเดตกิจกรรมล่าสุด..."
        }

        Thread {
            val result = runCatching { PattayaEventsRepository.fetchEvents() }
            runOnUiThread {
                binding.eventsProgress.visibility = View.GONE
                binding.eventsRefreshButton.isEnabled = true

                val events = result.getOrNull()
                if (events == null) {
                    if (binding.eventsList.childCount == 0) {
                        binding.eventsEmpty.visibility = View.VISIBLE
                        binding.eventsEmpty.text =
                            "ไม่สามารถโหลดกิจกรรมได้ในขณะนี้\nแตะปุ่ม อัปเดต เพื่อลองอีกครั้ง"
                    }
                    binding.eventsUpdated.text = "เชื่อมต่อ API ไม่สำเร็จ"
                    return@runOnUiThread
                }

                loadedEvents = events
                renderEventCategoryFilters()
                renderFilteredEvents()

                val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("th", "TH")).format(Date())
                binding.eventsUpdated.text =
                    "อัปเดตล่าสุด: " + time + " • ข้อมูลจาก khunsri.com"
            }
        }.start()
    }

    private fun renderEventCategoryFilters() {
        val categories = loadedEvents
            .mapNotNull { it.category?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()

        if (selectedEventCategory != null && categories.none { it == selectedEventCategory }) {
            selectedEventCategory = null
        }

        binding.eventsCategoryChips.removeAllViews()

        val allChip = Chip(this).apply {
            id = View.generateViewId()
            text = "ทั้งหมด"
            isCheckable = true
            isChecked = selectedEventCategory == null
            setOnClickListener {
                selectedEventCategory = null
                renderEventCategoryFilters()
                renderFilteredEvents()
            }
        }
        binding.eventsCategoryChips.addView(allChip)

        categories.forEach { category ->
            binding.eventsCategoryChips.addView(Chip(this).apply {
                id = View.generateViewId()
                text = category
                isCheckable = true
                isChecked = selectedEventCategory == category
                setOnClickListener {
                    selectedEventCategory = category
                    renderEventCategoryFilters()
                    renderFilteredEvents()
                }
            })
        }
    }

    private fun renderFilteredEvents() {
        val filtered = loadedEvents.filter { event ->
            val categoryOk = selectedEventCategory == null ||
                event.category.equals(selectedEventCategory, ignoreCase = true)

            val dateOk = selectedEventDateMillis == null ||
                eventOccursOn(event, selectedEventDateMillis!!)

            categoryOk && dateOk
        }.sortedWith(
            compareBy<PattayaEventsRepository.PattayaEvent> {
                parseEventDate(it.startDateText) ?: Long.MAX_VALUE
            }.thenBy { it.title }
        )

        binding.eventsList.removeAllViews()
        binding.eventsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        binding.eventsFilterSummary.text = buildString {
            append("พบ ")
            append(filtered.size)
            append(" กิจกรรม")
            selectedEventCategory?.let {
                append(" • หมวด ")
                append(it)
            }
            selectedEventDateMillis?.let {
                append(" • ")
                append(SimpleDateFormat("dd/MM/yyyy", Locale("th", "TH")).format(Date(it)))
            }
        }

        if (filtered.isEmpty()) {
            binding.eventsEmpty.text =
                if (loadedEvents.isEmpty()) "ขณะนี้ยังไม่มีกิจกรรมที่แสดงในระบบ"
                else "ไม่พบกิจกรรมตามวันที่หรือหมวดหมู่ที่เลือก"
            return
        }

        filtered.forEach { event ->
            binding.eventsList.addView(createEventCard(event))
        }
    }

    private fun eventOccursOn(
        event: PattayaEventsRepository.PattayaEvent,
        selectedDateMillis: Long
    ): Boolean {
        val selected = startOfDay(selectedDateMillis)
        val start = parseEventDate(event.startDateText)
        val end = parseEventDate(event.endDateText) ?: start

        if (start == null && end == null) return false
        val rangeStart = start ?: end ?: return false
        val rangeEnd = end ?: rangeStart

        return selected in minOf(rangeStart, rangeEnd)..maxOf(rangeStart, rangeEnd)
    }

    private fun parseEventDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        val candidates = buildList {
            Regex("""\d{4}-\d{2}-\d{2}""").find(text)?.value?.let(::add)
            Regex("""\d{1,2}/\d{1,2}/\d{4}""").find(text)?.value?.let(::add)
            Regex("""\d{1,2}-\d{1,2}-\d{4}""").find(text)?.value?.let(::add)
        }

        val patterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy")
        candidates.forEach { candidate ->
            patterns.forEach { pattern ->
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                    }.parse(candidate)
                }.getOrNull()

                if (parsed != null) return startOfDay(parsed.time)
            }
        }
        return null
    }

    private fun startOfDay(timeMillis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun createEventCard(event: PattayaEventsRepository.PattayaEvent): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.pattaya_border))
            setCardBackgroundColor(getColor(R.color.pattaya_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(46), dp(28), dp(46), dp(28))
            setImageResource(R.drawable.logo_pattaya_city)
            contentDescription = "รูปปกกิจกรรม " + event.title
        }
        box.addView(cover)

        event.imageUrl?.takeIf { it.startsWith("http") }?.let { imageUrl ->
            loadEventImage(cover, imageUrl)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(14))
        }

        event.category?.takeIf { it.isNotBlank() }?.let { category ->
            content.addView(TextView(this).apply {
                text = "🏷️ " + category
                textSize = 10.5f
                setTextColor(getColor(R.color.pattaya_blue))
                setTypeface(typeface, Typeface.BOLD)
            })
        }

        content.addView(TextView(this).apply {
            text = event.title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.pattaya_text))
            setPadding(0, dp(3), 0, 0)
        })

        event.dateText?.takeIf { it.isNotBlank() }?.let { dateText ->
            content.addView(TextView(this).apply {
                text = "📅 " + dateText
                textSize = 11.5f
                setTextColor(getColor(R.color.pattaya_text_muted))
                setPadding(0, dp(6), 0, 0)
            })
        }

        event.location?.takeIf { it.isNotBlank() }?.let { location ->
            content.addView(TextView(this).apply {
                text = "📍 " + location
                textSize = 11.5f
                maxLines = 3
                setTextColor(getColor(R.color.pattaya_text_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }

        event.description?.takeIf { it.isNotBlank() }?.let { description ->
            content.addView(TextView(this).apply {
                text = description
                textSize = 11.5f
                maxLines = 6
                setTextColor(getColor(R.color.pattaya_text))
                setPadding(0, dp(8), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        if (!event.location.isNullOrBlank() ||
            (!event.latitude.isNullOrBlank() && !event.longitude.isNullOrBlank()) ||
            !event.mapUrl.isNullOrBlank()
        ) {
            actions.addView(MaterialButton(this).apply {
                text = "🗺️ นำทาง"
                isAllCaps = false
                setOnClickListener { openEventMap(event) }
            })
        }

        event.detailUrl?.takeIf { it.startsWith("http") }?.let { url ->
            actions.addView(MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "รายละเอียด"
                isAllCaps = false
                setOnClickListener { openExternalUrl(url) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(8)
                }
            })
        }

        if (actions.childCount > 0) {
            content.addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            })
        }

        box.addView(content)
        card.addView(box)
        return card
    }

    private fun loadEventImage(imageView: ImageView, imageUrl: String) {
        imageView.tag = imageUrl
        Thread {
            val bitmap = runCatching {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PattayaCCTVViewer/2.6 Android")
                }

                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            if (output.size() > 8 * 1024 * 1024) break
                        }
                        output.toByteArray()
                    }
                    decodeEventImage(bytes)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (bitmap != null) {
                runOnUiThread {
                    if (imageView.tag == imageUrl) {
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }.start()
    }

    private fun decodeEventImage(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 900) {
            sample *= 2
        }

        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun openEventMap(event: PattayaEventsRepository.PattayaEvent) {
        val coordinate = if (!event.latitude.isNullOrBlank() && !event.longitude.isNullOrBlank()) {
            event.latitude + "," + event.longitude
        } else {
            null
        }
        val query = coordinate ?: event.location?.takeIf { it.isNotBlank() }

        if (query != null) {
            val navigationUri = Uri.parse("google.navigation:q=" + Uri.encode(query))
            val googleMapsIntent = Intent(Intent.ACTION_VIEW, navigationUri).apply {
                setPackage("com.google.android.apps.maps")
            }

            if (googleMapsIntent.resolveActivity(packageManager) != null) {
                startActivity(googleMapsIntent)
                return
            }

            val webMaps = "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query)
            openExternalUrl(webMaps)
            return
        }

        event.mapUrl?.takeIf { it.startsWith("http") }?.let(::openExternalUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun focusCameraSearch() {
        binding.webView.evaluateJavascript(
            """
            (function(){
              var tries = 0;
              var timer = setInterval(function(){
                var el = document.querySelector('input[placeholder*="Search camera"], input[type="search"], input[placeholder*="camera" i]');
                if(el){
                  clearInterval(timer);
                  el.scrollIntoView({behavior:'smooth',block:'center'});
                  el.focus();
                  el.click();
                }
                if(++tries > 16) clearInterval(timer);
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
              window.__pattayaLastCameraLabel = null;

              function findId(text){
                if(!text) return null;
                var m = String(text).match(/\b(?:CC|NC|SC|RC)-\d+\b|\bCAM\s*-?\s*\d+\b/i);
                if(!m) return null;
                return m[0].replace(/\s+/g,'').replace(/^CAM(\d+)$/i,'CAM-$1').toUpperCase();
              }

              function remember(text){
                if(!text || text.length > 500) return;
                var clean = String(text).replace(/\s+/g,' ').trim();
                var id = findId(clean);
                if(id){
                  window.__pattayaLastCameraId = id;
                  if(clean.length <= 220) window.__pattayaLastCameraLabel = clean;
                }
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

    private fun detectCurrentCameraForRecent() {
        binding.webView.evaluateJavascript(
            """
            (function(){
              function normalise(value){
                if(!value) return null;
                var m = String(value).match(/\b(?:CC|NC|SC|RC)-\d+\b|\bCAM\s*-?\s*\d+\b/i);
                if(!m) return null;
                return m[0].replace(/\s+/g,'').replace(/^CAM(\d+)$/i,'CAM-$1').toUpperCase();
              }

              function compact(text){
                return String(text || '').replace(/\s+/g,' ').trim();
              }

              function labelFor(id){
                var best = compact(window.__pattayaLastCameraLabel || '');
                if(best && normalise(best) === id && best.length <= 220) return best;

                var selectors = '[aria-selected="true"],[class*="selected"],[class*="active"],[class*="popup"],[class*="modal"],button,a,li,[role="button"],div,span,p';
                var nodes = document.querySelectorAll(selectors);
                var candidates = [];
                for(var i=0; i<nodes.length; i++){
                  var text = compact(nodes[i].innerText || nodes[i].textContent || '');
                  if(!text || text.length > 220) continue;
                  if(normalise(text) === id) candidates.push(text);
                }
                candidates.sort(function(a,b){ return a.length - b.length; });
                return candidates.length ? candidates[0] : null;
              }

              var id = normalise(decodeURIComponent(window.location.href || ''));
              if(!id && window.__pattayaLastCameraId) id = normalise(window.__pattayaLastCameraId);

              if(!id){
                var nodes = document.querySelectorAll('[aria-selected="true"],[class*="selected"],[class*="active"],[class*="popup"],[class*="modal"]');
                for(var i=0; i<nodes.length; i++){
                  var text = compact(nodes[i].innerText || nodes[i].textContent || '');
                  if(text && text.length < 500){
                    id = normalise(text);
                    if(id) break;
                  }
                }
              }

              if(!id) return null;
              return JSON.stringify({id:id,label:labelFor(id)});
            })();
            """.trimIndent()
        ) { jsValue ->
            val info = parseCameraInfo(jsValue) ?: return@evaluateJavascript
            info.label?.let { saveCameraName(info.id, it) }
            trackRecentCamera(info.id)
        }
    }

    private fun trackRecentCamera(cameraId: String) {
        val id = normalizeCameraId(cameraId) ?: return
        val now = System.currentTimeMillis()
        if (id.equals(lastTrackedCameraId, ignoreCase = true) && now - lastTrackedAt < 30_000L) return

        lastTrackedCameraId = id
        lastTrackedAt = now
        recordRecent(id)
        captureRecentThumbnail(id)
        renderDashboardRecents()
    }

    private fun thumbnailFile(cameraId: String): File {
        val dir = File(filesDir, "camera_thumbnails").apply { mkdirs() }
        val safeId = cameraId.replace(Regex("""[^A-Za-z0-9_-]"""), "_")
        return File(dir, "$safeId.jpg")
    }

    private fun captureRecentThumbnail(cameraId: String) {
        val id = normalizeCameraId(cameraId) ?: return
        val webView = binding.webView
        if (!webView.isShown || webView.width <= 0 || webView.height <= 0) return

        webView.postDelayed({
            if (!webView.isShown || webView.width <= 0 || webView.height <= 0) return@postDelayed

            val saveBitmap: (Bitmap) -> Unit = { source ->
                val thumbnail = cropThumbnail(source)
                Thread {
                    runCatching {
                        FileOutputStream(thumbnailFile(id)).use { output ->
                            thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, output)
                        }
                    }
                    runOnUiThread {
                        if (binding.homePanel.visibility == View.VISIBLE) {
                            renderDashboardRecents()
                        }
                    }
                }.start()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val location = IntArray(2)
                webView.getLocationInWindow(location)
                val rect = Rect(
                    location[0],
                    location[1],
                    location[0] + webView.width,
                    location[1] + webView.height
                )
                val bitmap = Bitmap.createBitmap(
                    webView.width,
                    webView.height,
                    Bitmap.Config.ARGB_8888
                )
                runCatching {
                    PixelCopy.request(window, rect, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            saveBitmap(bitmap)
                        } else {
                            saveBitmap(drawWebViewBitmap(webView))
                        }
                    }, Handler(Looper.getMainLooper()))
                }.onFailure {
                    saveBitmap(drawWebViewBitmap(webView))
                }
            } else {
                saveBitmap(drawWebViewBitmap(webView))
            }
        }, 1400L)
    }

    private fun drawWebViewBitmap(view: WebView): Bitmap {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun cropThumbnail(source: Bitmap): Bitmap {
        if (source.width <= 1 || source.height <= 1) return source
        val targetAspect = 16f / 9f
        val sourceAspect = source.width.toFloat() / source.height.toFloat()

        return if (sourceAspect > targetAspect) {
            val newWidth = (source.height * targetAspect).toInt().coerceAtLeast(1)
            val left = ((source.width - newWidth) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, left, 0, newWidth, source.height)
        } else {
            val newHeight = (source.width / targetAspect).toInt().coerceAtLeast(1)
            val top = ((source.height - newHeight) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, 0, top, source.width, newHeight)
        }
    }

    private fun normalizeCameraId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var id = value.trim().uppercase(Locale.US)
        id = id.replace(Regex("""^CAM\s*-?\s*(\d+)$"""), "CAM-$1")
        return if (Regex("""^(?:(?:CC|NC|SC|RC)-\d+|CAM-\d+)$""").matches(id)) id else null
    }

    private fun parseCameraInfo(jsValue: String?): CameraInfo? {
        val raw = decodeJavascriptString(jsValue) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val id = normalizeCameraId(obj.optString("id")) ?: return@runCatching null
            val label = cleanCameraLabel(id, obj.optString("label").takeIf { it.isNotBlank() })
            CameraInfo(id, label)
        }.getOrNull()
    }

    private fun cleanCameraLabel(cameraId: String, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.replace(Regex("""\s+"""), " ").trim()
        text = text.replace(cameraId, "", ignoreCase = true)
        text = text.replace(Regex("""^(?:กล้อง|camera|cctv)\s*[:\-–—]*\s*""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""^[\-–—:|•·]+|[\-–—:|•·]+$"""), "").trim()
        if (text.equals("Live View", ignoreCase = true) ||
            text.equals("Camera List", ignoreCase = true) ||
            text.length < 2 ||
            text.length > 120
        ) return null
        return text
    }

    private fun saveCameraName(cameraId: String, rawLabel: String?) {
        val id = normalizeCameraId(cameraId) ?: return
        val label = cleanCameraLabel(id, rawLabel) ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("camera_name_$id", label)
            .apply()
    }

    private fun cameraName(cameraId: String): String? {
        val id = normalizeCameraId(cameraId) ?: return null
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("camera_name_$id", null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun favorites(): MutableSet<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    private fun saveFavorites(values: Set<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, values)
            .apply()
        renderDashboardFavorites()
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
              function compact(text){
                return String(text || '').replace(/\s+/g,' ').trim();
              }
              function labelFor(id){
                var best = compact(window.__pattayaLastCameraLabel || '');
                if(best && normalise(best) === id && best.length <= 220) return best;
                var nodes = document.querySelectorAll('[aria-selected="true"],[class*="selected"],[class*="active"],[class*="popup"],[class*="modal"],button,a,li,[role="button"],div,span,p');
                var candidates = [];
                for(var i=0; i<nodes.length; i++){
                  var text = compact(nodes[i].innerText || nodes[i].textContent || '');
                  if(!text || text.length > 220) continue;
                  if(normalise(text) === id) candidates.push(text);
                }
                candidates.sort(function(a,b){ return a.length - b.length; });
                return candidates.length ? candidates[0] : null;
              }

              var id = normalise(decodeURIComponent(window.location.href || ''));
              if(!id && window.__pattayaLastCameraId) id = normalise(window.__pattayaLastCameraId);
              if(!id){
                var nodes = document.querySelectorAll('[aria-selected="true"],[class*="selected"],[class*="active"],[class*="popup"],[class*="modal"]');
                for(var i=0; i<nodes.length; i++){
                  var text = compact(nodes[i].innerText || nodes[i].textContent || '');
                  if(text && text.length < 500){
                    id = normalise(text);
                    if(id) break;
                  }
                }
              }
              if(!id) return null;
              return JSON.stringify({id:id,label:labelFor(id)});
            })();
            """.trimIndent()
        ) { jsValue ->
            val info = parseCameraInfo(jsValue)
            val selectedId = info?.id ?: extractCameraId(binding.webView.url)

            if (selectedId.isNullOrBlank()) {
                Toast.makeText(this, R.string.favorite_choose_camera, Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }

            info?.label?.let { saveCameraName(selectedId, it) }
            trackRecentCamera(selectedId)

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
            renderDashboardRecents()
            renderDashboardFavorites()

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

        var decoded: String = rawUrl
        repeat(3) {
            val next = Uri.decode(decoded)
            if (next != decoded) decoded = next
        }

        Regex("""/live-cctv/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizeCameraId(it) }
            ?.let { return it }

        return runCatching {
            val uri = Uri.parse(rawUrl)
            uri.getQueryParameter("liff.state")
                ?.let { Uri.decode(it) }
                ?.let {
                    Regex("""/live-cctv/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.getOrNull(1)
                }
                ?.let { normalizeCameraId(it) }
        }.getOrNull()
    }

    private fun cameraUrl(cameraId: String): String =
        Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("liff.state", "/live-cctv/$cameraId")
            .build()
            .toString()

    private fun decodeJavascriptString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return runCatching {
            JSONObject("{\"value\":$value}").getString("value")
        }.getOrNull()
    }

    private fun openFavoriteCamera(cameraId: String) {
        val normalized = normalizeCameraId(cameraId) ?: return
        pendingFavoriteCameraId = normalized
        binding.homePanel.visibility = View.GONE
        binding.favoritesPanel.visibility = View.GONE
        binding.morePanel.visibility = View.GONE
        binding.viewerPanel.visibility = View.VISIBLE

        Toast.makeText(
            this,
            getString(R.string.favorite_opening, normalized),
            Toast.LENGTH_SHORT
        ).show()

        binding.webView.loadUrl(BASE_URL)
    }

    private fun openPendingFavoriteCamera() {
        val cameraId = pendingFavoriteCameraId ?: return
        val target = JSONObject.quote(cameraId)

        binding.webView.evaluateJavascript(
            """
            (function(target){
              var attempts = 0;
              var timer = setInterval(function(){
                attempts++;

                var input = document.querySelector('input[placeholder*="Search camera"], input[type="search"], input[placeholder*="camera" i]');
                if(input){
                  var proto = Object.getPrototypeOf(input);
                  var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
                  if(desc && desc.set) desc.set.call(input, target); else input.value = target;
                  input.dispatchEvent(new Event('input', {bubbles:true}));
                  input.dispatchEvent(new Event('change', {bubbles:true}));
                }

                var all = Array.from(document.querySelectorAll('button,a,[role="button"],li,tr,div,span,td'));
                var matches = all.filter(function(el){
                  var text = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim().toUpperCase();
                  if(!text || text.length > 300) return false;
                  return text === target || text.indexOf(target + ' ') === 0 || text.indexOf(' ' + target) >= 0;
                });

                matches.sort(function(a,b){
                  return ((a.innerText||a.textContent||'').length - (b.innerText||b.textContent||'').length);
                });

                if(matches.length){
                  var el = matches[0].closest('button,a,[role="button"],li,tr,[class*="camera"]') || matches[0];
                  try{
                    el.scrollIntoView({behavior:'smooth',block:'center'});
                    el.click();
                    window.__pattayaLastCameraId = target;
                    clearInterval(timer);
                  }catch(e){}
                }

                if(attempts >= 30) clearInterval(timer);
              }, 400);
              return 'scheduled';
            })($target);
            """.trimIndent(),
            null
        )

        pendingFavoriteCameraId = null
        recordRecent(cameraId)
        updateFavoriteButton(cameraUrl(cameraId))
        renderDashboardRecents()
    }

    private fun renderFavorites() {
        val set = favorites().toList().sorted()
        binding.favoritesList.removeAllViews()
        binding.favoritesEmpty.visibility = if (set.isEmpty()) View.VISIBLE else View.GONE

        set.forEach { url ->
            val id = extractCameraId(url) ?: return@forEach
            binding.favoritesList.addView(createFavoriteRow(id, true))
        }
    }

    private fun renderDashboardFavorites() {
        val ids = favorites().mapNotNull { extractCameraId(it) }.distinct().sorted().take(5)
        binding.dashboardFavoritesList.removeAllViews()
        binding.dashboardFavoritesEmpty.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE

        ids.forEach { id ->
            binding.dashboardFavoritesList.addView(createDashboardCameraCard(id, "❤️ กล้องโปรด"))
        }
    }

    private fun createFavoriteRow(id: String, removable: Boolean): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.pattaya_border))
            setCardBackgroundColor(getColor(R.color.pattaya_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(9), dp(11))
        }

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener { openFavoriteCamera(id) }
        }

        textBox.addView(TextView(this).apply {
            text = "📹 กล้อง $id"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.pattaya_text))
        })

        textBox.addView(TextView(this).apply {
            text = cameraName(id)?.let { "📍 $it" } ?: "📍 เปิดกล้องอีกครั้งเพื่อดึงชื่อสถานที่"
            textSize = 10.5f
            maxLines = 2
            setTextColor(getColor(R.color.pattaya_text_muted))
            setPadding(0, dp(3), dp(6), 0)
        })

        val open = MaterialButton(this).apply {
            text = getString(R.string.open)
            isAllCaps = false
            setOnClickListener { openFavoriteCamera(id) }
        }

        row.addView(textBox)
        row.addView(open)

        if (removable) {
            val remove = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "×"
                textSize = 19f
                minimumWidth = dp(44)
                setOnClickListener {
                    val current = favorites()
                    current.removeAll { extractCameraId(it).equals(id, ignoreCase = true) }
                    saveFavorites(current)
                    renderFavorites()
                }
            }
            row.addView(remove)
        }

        card.addView(row)
        return card
    }

    private fun createDashboardCameraCard(id: String, caption: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.pattaya_border))
            setCardBackgroundColor(getColor(R.color.pattaya_surface))
            layoutParams = LinearLayout.LayoutParams(dp(210), dp(202)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { openFavoriteCamera(id) }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(102)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "ภาพล่าสุดจากกล้อง $id"

            val file = thumbnailFile(id)
            val bitmap = if (file.exists()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            } else null

            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                setImageResource(R.drawable.ic_camera_placeholder)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(34), dp(22), dp(34), dp(22))
            }
        }

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }

        textBox.addView(TextView(this).apply {
            text = caption
            textSize = 9.5f
            setTextColor(getColor(R.color.pattaya_text_muted))
        })

        textBox.addView(TextView(this).apply {
            text = "กล้อง $id"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.pattaya_text))
            setPadding(0, dp(2), 0, 0)
        })

        textBox.addView(TextView(this).apply {
            text = cameraName(id)?.let { "📍 $it" } ?: "📍 กำลังรอชื่อสถานที่จากต้นฉบับ"
            textSize = 9.5f
            maxLines = 2
            setTextColor(getColor(R.color.pattaya_text_muted))
            setPadding(0, dp(2), 0, 0)
        })

        textBox.addView(TextView(this).apply {
            text = "แตะเพื่อเปิดดูภาพสด"
            textSize = 9.5f
            setTextColor(getColor(R.color.pattaya_blue))
            setPadding(0, dp(2), 0, 0)
        })

        box.addView(preview)
        box.addView(textBox)
        card.addView(box)
        return card
    }

    private fun loadRecents(): MutableList<RecentCamera> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENTS, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()

        return raw.split("|")
            .mapNotNull { item ->
                val parts = item.split("@")
                if (parts.size != 2) return@mapNotNull null
                val id = normalizeCameraId(parts[0]) ?: return@mapNotNull null
                val time = parts[1].toLongOrNull() ?: return@mapNotNull null
                RecentCamera(id, time)
            }
            .sortedByDescending { it.viewedAt }
            .toMutableList()
    }

    private fun saveRecents(items: List<RecentCamera>) {
        val raw = items.take(10).joinToString("|") { "${it.id}@${it.viewedAt}" }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENTS, raw)
            .apply()
    }

    private fun recordRecent(cameraId: String) {
        val id = normalizeCameraId(cameraId) ?: return
        val items = loadRecents()
        items.removeAll { it.id.equals(id, ignoreCase = true) }
        items.add(0, RecentCamera(id, System.currentTimeMillis()))
        saveRecents(items)
    }

    private fun renderDashboardRecents() {
        val items = loadRecents().take(5)
        binding.dashboardRecentList.removeAllViews()
        binding.dashboardRecentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEach { item ->
            binding.dashboardRecentList.addView(
                createDashboardCameraCard(item.id, "🕘 ${timeAgo(item.viewedAt)}")
            )
        }
    }

    private fun timeAgo(time: Long): String {
        val diffMinutes = ((System.currentTimeMillis() - time).coerceAtLeast(0L) / 60000L)
        return when {
            diffMinutes < 1 -> "เมื่อสักครู่"
            diffMinutes < 60 -> "$diffMinutes นาทีที่แล้ว"
            diffMinutes < 1440 -> "${diffMinutes / 60} ชั่วโมงที่แล้ว"
            else -> "${diffMinutes / 1440} วันที่แล้ว"
        }
    }

    private fun showRecentDialog() {
        val items = loadRecents()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.dashboard_empty_recents, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = items.map {
            val place = cameraName(it.id)?.let { name -> " • $name" }.orEmpty()
            "กล้อง ${it.id}$place • ${timeAgo(it.viewedAt)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_recents_title))
            .setItems(labels) { _, which -> openFavoriteCamera(items[which].id) }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private data class ImportantContact(
        val icon: String,
        val name: String,
        val number: String,
        val detail: String
    )

    private fun showImportantContacts() {
        val contacts = listOf(
            ImportantContact("🏙️", "Pattaya Contact Center", "1337", "สอบถามและแจ้งเรื่องเมืองพัทยา"),
            ImportantContact("📹", "ศูนย์ข้อมูล CCTV เมืองพัทยา", "038253299", "ติดต่อเกี่ยวกับระบบ CCTV Streaming"),
            ImportantContact("🏢", "ศาลาว่าการเมืองพัทยา", "038253100", "ติดต่อสำนักงานเมืองพัทยา"),
            ImportantContact("🚓", "เหตุด่วนเหตุร้าย", "191", "ตำรวจ"),
            ImportantContact("🚑", "การแพทย์ฉุกเฉิน", "1669", "เจ็บป่วยหรืออุบัติเหตุฉุกเฉิน"),
            ImportantContact("🔥", "ดับเพลิง", "199", "แจ้งเหตุเพลิงไหม้"),
            ImportantContact("👮", "ตำรวจท่องเที่ยว", "1155", "ช่วยเหลือนักท่องเที่ยว"),
            ImportantContact("⛈️", "ป้องกันและบรรเทาสาธารณภัย", "1784", "แจ้งเหตุสาธารณภัย")
        )

        val labels = contacts.map {
            "${it.icon} ${it.name}\n${it.number} • ${it.detail}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("☎️ เบอร์สำคัญเมืองพัทยา")
            .setItems(labels) { _, which ->
                dialNumber(contacts[which].number)
            }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun dialNumber(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }.onFailure {
            Toast.makeText(this, "ไม่พบแอปโทรศัพท์ในเครื่อง", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAlertsInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("แจ้งเตือนและข้อมูลสำคัญ")
            .setMessage("ขณะนี้สามารถตรวจสอบสภาพอากาศ ฝน การจราจร และสภาพพื้นที่จาก Dashboard และกล้องสดได้ ส่วน Push Notification อัตโนมัติจะเปิดใช้งานเมื่อมีแหล่งข้อมูลแจ้งเตือนที่เหมาะสมและเชื่อถือได้")
            .setPositiveButton("ดูอากาศ") { _, _ -> openExternalUrl(LiveInfoRepository.WEATHER_DETAIL_URL) }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun setupLiveInfo() {
        binding.weatherSource.text = "Open-Meteo • แตะดูพยากรณ์จากกรมอุตุนิยมวิทยา"
        binding.oilSource.text = "กระทรวงพลังงาน • แตะดูข้อมูลต้นทาง"
        binding.goldSource.text = "สมาคมค้าทองคำ • แตะดูข้อมูลต้นทาง"
    }

    private fun loadCachedInfo() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(KEY_WEATHER_CACHE, null)?.let {
            binding.weatherValue.text = it
            updateWeatherSummary(it)
        }
        prefs.getString(KEY_OIL_CACHE, null)?.let {
            binding.oilValue.text = it
            updateOilSummary(it)
        }
        prefs.getString(KEY_GOLD_CACHE, null)?.let {
            binding.goldValue.text = it
            updateGoldSummary(it)
        }
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
            val result = runCatching { LiveInfoRepository.fetchWeather() }
            runOnUiThread {
                val data = result.getOrNull()
                if (data != null) {
                    binding.weatherValue.text = data.summary
                    cache(KEY_WEATHER_CACHE, data.summary)
                    updateWeatherSummary(data.summary)
                } else if (getCached(KEY_WEATHER_CACHE) == null) {
                    binding.weatherValue.setText(R.string.info_unavailable)
                }
                updateInfoTimestamp()
            }
        }.start()

        Thread {
            val result = runCatching { LiveInfoRepository.fetchOil() }
            runOnUiThread {
                val data = result.getOrNull()
                if (data != null) {
                    binding.oilValue.text = data.summary
                    cache(KEY_OIL_CACHE, data.summary)
                    updateOilSummary(data.summary)
                } else if (getCached(KEY_OIL_CACHE) == null) {
                    binding.oilValue.setText(R.string.info_unavailable)
                }
                updateInfoTimestamp()
            }
        }.start()

        Thread {
            val result = runCatching { LiveInfoRepository.fetchGold() }
            runOnUiThread {
                val data = result.getOrNull()
                if (data != null) {
                    binding.goldValue.text = data.summary
                    cache(KEY_GOLD_CACHE, data.summary)
                    updateGoldSummary(data.summary)
                } else if (getCached(KEY_GOLD_CACHE) == null) {
                    binding.goldValue.setText(R.string.info_unavailable)
                }
                updateInfoTimestamp()
            }
        }.start()
    }

    private fun updateWeatherSummary(summary: String) {
        val value = Regex("""(-?\d+(?:\.\d+)?)°C""").find(summary)?.groupValues?.getOrNull(1)
        binding.weatherSummaryValue.text = value?.let { "$it°C" } ?: "--°C"
    }

    private fun updateOilSummary(summary: String) {
        val value = Regex("""Gasohol 95\s+฿([0-9.]+)""", RegexOption.IGNORE_CASE)
            .find(summary)?.groupValues?.getOrNull(1)
        binding.oilSummaryValue.text = value ?: "--.--"
    }

    private fun updateGoldSummary(summary: String) {
        val value = Regex("""ขายออก ฿([0-9,.]+)""")
            .find(summary)?.groupValues?.getOrNull(1)
        binding.goldSummaryValue.text = value ?: "--,---"
    }

    private fun cache(key: String, value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun getCached(key: String): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null)

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

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme == "https" || scheme == "http") &&
            host != null &&
            host.endsWith("pattaya.go.th")
        ) {
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
        binding.mainShell.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        setFullscreenImmersive(false)
    }

    private fun setFullscreenImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        cameraTrackerHandler.removeCallbacks(cameraTrackerRunnable)
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
