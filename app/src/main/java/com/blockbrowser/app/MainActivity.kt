package com.blockbrowser.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.blockbrowser.app.browser.AdBlockManager
import com.blockbrowser.app.browser.BlockingWebViewClient
import com.blockbrowser.app.browser.BrowserTab
import com.blockbrowser.app.data.DownloadRecord
import com.blockbrowser.app.data.DownloadsManager
import com.blockbrowser.app.data.FavoriteItem
import com.blockbrowser.app.data.FavoritesManager
import com.blockbrowser.app.data.HistoryItem
import com.blockbrowser.app.data.HistoryManager
import com.blockbrowser.app.databinding.ActivityMainBinding
import com.blockbrowser.app.databinding.DialogDevtoolsBinding
import com.blockbrowser.app.databinding.DialogDownloadsBinding
import com.blockbrowser.app.databinding.DialogExtensionDetailsBinding
import com.blockbrowser.app.databinding.DialogExtensionWebviewBinding
import com.blockbrowser.app.databinding.DialogExtensionsBinding
import com.blockbrowser.app.databinding.DialogExtensionsPopupBinding
import com.blockbrowser.app.databinding.DialogFavoritesBinding
import com.blockbrowser.app.databinding.DialogHistoryBinding
import com.blockbrowser.app.databinding.DialogSettingsBinding
import com.blockbrowser.app.databinding.DialogSourceViewerBinding
import com.blockbrowser.app.devtools.ConsoleMessageItem
import com.blockbrowser.app.devtools.DevToolsManager
import com.blockbrowser.app.devtools.UserAgentManager
import com.blockbrowser.app.devtools.UserAgentMode
import com.blockbrowser.app.extensions.ExtensionManager
import com.blockbrowser.app.extensions.InstalledXpiExtension
import com.blockbrowser.app.extensions.WebExtensionBridge
import com.blockbrowser.app.extensions.XpiManager
import com.blockbrowser.app.settings.SearchEngine
import com.blockbrowser.app.settings.SettingsManager
import com.blockbrowser.app.theme.AppTheme
import com.blockbrowser.app.theme.ThemeManager
import com.blockbrowser.app.vpn.AdBlockVpnService
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: Long = -1
    private var nextTabId: Long = 1
    private var vpnActive = false

    private val xpiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            installXpiFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this) // must run before setContentView
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AdBlockManager.ensureLoaded(this)
        ExtensionManager.ensureLoaded(this)

        setupAddressBar()
        setupNavButtons()
        setupMenuButton()

        val initialUrl = intent?.dataString?.takeIf { it.isNotBlank() } ?: SettingsManager.getHomePage(this)
        openNewTab(initialUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.takeIf { it.isNotBlank() }?.let { url ->
            openNewTab(url)
        }
    }

    // ---------- Tabs ----------

    private fun openNewTab(startUrl: String, uaMode: UserAgentMode? = null) {
        val defaultDesktop = SettingsManager.isDefaultDesktopSite(this)
        val tabUaMode = uaMode ?: SettingsManager.getDefaultUserAgentMode(this)
        val webView = createConfiguredWebView(isDesktop = defaultDesktop, uaMode = tabUaMode)
        val tab = BrowserTab(
            id = nextTabId++,
            webView = webView,
            isDesktopMode = defaultDesktop,
            uaMode = tabUaMode
        )
        tabs.add(tab)
        showTab(tab.id)
        applyTabUserAgent(tab, startUrl, reload = false)
        webView.loadUrl(startUrl)
    }

    private fun createConfiguredWebView(isDesktop: Boolean, uaMode: UserAgentMode): WebView {
        val webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = SettingsManager.isJavaScriptEnabled(this)
        settings.domStorageEnabled = SettingsManager.isDomStorageEnabled(this)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        val effectiveUa = UserAgentManager.getEffectiveUserAgent(uaMode, null, isDesktop)
        settings.userAgentString = effectiveUa
        val isDesktopLike = isDesktop || effectiveUa != null
        settings.useWideViewPort = isDesktopLike
        settings.loadWithOverviewMode = isDesktopLike

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                DevToolsManager.addConsoleMessage(
                    ConsoleMessageItem(
                        level = consoleMessage.messageLevel().name,
                        message = consoleMessage.message(),
                        source = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                    )
                )
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (fileName.endsWith(".xpi", ignoreCase = true) || mimetype == "application/x-xpinstall" || url.endsWith(".xpi", ignoreCase = true)) {
                showXpiDownloadOptionsDialog(url, userAgent, contentDisposition, mimetype, fileName)
            } else {
                DownloadsManager.enqueueDownload(this, url, userAgent, contentDisposition, mimetype)
            }
        }

        webView.webViewClient = BlockingWebViewClient(this) { title, url, loading ->
            runOnUiThread {
                val currentTab = currentTabOrNull()
                if (currentTab != null && currentTab.webView == webView) {
                    binding.addressBar.setText(url)
                    binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                    if (title.isNotBlank()) {
                        currentTab.title = title
                    }
                    if (url.isNotBlank()) {
                        currentTab.url = url
                    }
                    updateFavoriteButtonIcon()
                }
            }
        }

        binding.webViewContainer.addView(
            webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.visibility = View.GONE
        return webView
    }

    private fun showTab(tabId: Long) {
        activeTabId = tabId
        tabs.forEach { it.webView.visibility = if (it.id == tabId) View.VISIBLE else View.GONE }
        tabs.find { it.id == tabId }?.let {
            val url = it.webView.url ?: it.url
            binding.addressBar.setText(url)
        }
        updateFavoriteButtonIcon()
    }

    private fun closeTab(tabId: Long) {
        val tab = tabs.find { it.id == tabId } ?: return
        binding.webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            openNewTab(SettingsManager.getHomePage(this))
        } else if (activeTabId == tabId) {
            showTab(tabs.last().id)
        }
        updateFavoriteButtonIcon()
    }

    private fun currentTabOrNull(): BrowserTab? = tabs.find { it.id == activeTabId }

    private fun currentWebViewOrNull(): WebView? = currentTabOrNull()?.webView

    private fun updateFavoriteButtonIcon() {
        val currentTab = currentTabOrNull()
        val url = currentTab?.webView?.url ?: currentTab?.url.orEmpty()
        val isFav = FavoritesManager.isFavorite(this, url)
        binding.favoriteButton.setImageResource(if (isFav) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun showTabSwitcher() {
        val names = tabs.map { "${it.title} — ${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(names) { _, which -> showTab(tabs[which].id) }
            .setNeutralButton("Tab schließen") { _, _ ->
                currentTabOrNull()?.let { closeTab(it.id) }
            }
            .setPositiveButton("Neuer Tab") { _, _ -> openNewTab(SettingsManager.getHomePage(this)) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Address bar / navigation ----------

    private fun setupAddressBar() {
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) {
                navigateTo(binding.addressBar.text.toString())
                true
            } else false
        }
    }

    private fun navigateTo(input: String) {
        val url = normalizeToUrl(input)
        val tab = currentTabOrNull() ?: return
        applyTabUserAgent(tab, url, reload = false)
        tab.webView.loadUrl(url)
    }

    private fun normalizeToUrl(input: String): String {
        val trimmed = input.trim()
        val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            looksLikeUrl -> "https://$trimmed"
            else -> SettingsManager.buildSearchUrl(this, trimmed)
        }
    }

    private fun setupNavButtons() {
        binding.backButton.setOnClickListener {
            currentWebViewOrNull()?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.extensionsButton.setOnClickListener {
            showExtensionsPopup()
        }
        binding.favoriteButton.setOnClickListener {
            toggleCurrentFavorite()
        }
        binding.translateButton.setOnClickListener {
            translateCurrentPage()
        }
        binding.reloadButton.setOnClickListener {
            currentWebViewOrNull()?.reload()
        }
    }

    private fun toggleCurrentFavorite() {
        val currentTab = currentTabOrNull() ?: return
        val url = currentTab.webView.url ?: currentTab.url
        if (url.isBlank() || url.startsWith("about:")) {
            Toast.makeText(this, "Keine gültige Seite zum Speichern", Toast.LENGTH_SHORT).show()
            return
        }
        val title = currentTab.webView.title ?: currentTab.title
        if (FavoritesManager.isFavorite(this, url)) {
            FavoritesManager.removeByUrl(this, url)
            binding.favoriteButton.setImageResource(R.drawable.ic_star_border)
            Toast.makeText(this, R.string.favorites_removed, Toast.LENGTH_SHORT).show()
        } else {
            FavoritesManager.add(this, title, url)
            binding.favoriteButton.setImageResource(R.drawable.ic_star)
            Toast.makeText(this, R.string.favorites_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateCurrentPage() {
        val tab = currentTabOrNull() ?: return
        val currentUrl = tab.webView.url ?: tab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("about:")) {
            Toast.makeText(this, "Keine Seite zum Übersetzen geöffnet", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentUrl.contains("translate.google.com")) {
            tab.webView.reload()
            return
        }
        val translateUrl = "https://translate.google.com/translate?sl=auto&tl=de&u=${Uri.encode(currentUrl)}"
        applyTabUserAgent(tab, translateUrl, reload = false)
        tab.webView.loadUrl(translateUrl)
        Toast.makeText(this, "Seite wird auf Deutsch übersetzt…", Toast.LENGTH_SHORT).show()
    }

    private fun openCurrentUrlWithExternalApp() {
        val tab = currentTabOrNull() ?: return
        val currentUrl = tab.webView.url ?: tab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("about:")) {
            Toast.makeText(this, "Keine gültige URL zum Öffnen", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "Seite öffnen mit…"))
        }.onFailure {
            Toast.makeText(this, "Keine passende App gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- User-Agent handling ----------

    private fun applyTabUserAgent(tab: BrowserTab, targetUrl: String? = null, reload: Boolean = false) {
        val url = targetUrl ?: tab.webView.url ?: tab.url
        val effectiveUa = UserAgentManager.getEffectiveUserAgent(tab.uaMode, url, tab.isDesktopMode)
        val settings = tab.webView.settings
        if (settings.userAgentString != effectiveUa) {
            settings.userAgentString = effectiveUa
        }
        val isDesktopLike = tab.isDesktopMode || effectiveUa != null
        settings.useWideViewPort = isDesktopLike
        settings.loadWithOverviewMode = isDesktopLike
        if (reload) {
            tab.webView.reload()
        }
    }

    private fun toggleDesktopMode() {
        val tab = currentTabOrNull() ?: return
        tab.isDesktopMode = !tab.isDesktopMode
        applyTabUserAgent(tab, reload = true)
        val msg = if (tab.isDesktopMode) "Desktop-Website aktiviert" else "Mobile Website aktiviert"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Overflow menu ----------

    private fun setupMenuButton() {
        binding.menuButton.setOnClickListener { view -> showOverflowMenu(view) }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val currentTab = currentTabOrNull()
        val currentUrl = currentTab?.webView?.url ?: currentTab?.url.orEmpty()
        val isFav = FavoritesManager.isFavorite(this, currentUrl)
        val isDesktop = currentTab?.isDesktopMode == true

        if (currentUrl.lowercase().contains("addons.mozilla.org")) {
            popup.menu.add(0, 99, 0, "🦊 Auf Firefox-Erkennung schalten")
        }

        popup.menu.add(0, 1, 1, if (isFav) "★ ${getString(R.string.menu_favorites)}" else "☆ ${getString(R.string.menu_favorites)}")
        popup.menu.add(0, 2, 2, "🕒 ${getString(R.string.menu_history)}")
        popup.menu.add(0, 3, 3, "📥 ${getString(R.string.menu_downloads)}")
        popup.menu.add(0, 4, 4, "🧩 ${getString(R.string.menu_extensions)}")
        popup.menu.add(0, 5, 5, "🗂️ ${getString(R.string.menu_tabs)} (${tabs.size})")
        popup.menu.add(0, 6, 6, "🛠 ${getString(R.string.menu_devtools)}")
        popup.menu.add(0, 7, 7, "🌐 ${getString(R.string.menu_open_with)}")
        popup.menu.add(0, 8, 8, "⏩ ${getString(R.string.action_forward)}")
        popup.menu.add(0, 9, 9, "⚙ ${getString(R.string.menu_settings)}")
        popup.menu.add(0, 10, 10, if (isDesktop) "✓ ${getString(R.string.menu_desktop_site)}" else getString(R.string.menu_desktop_site))
        popup.menu.add(0, 11, 11, if (AdBlockManager.isEnabled) "✓ Werbeblocker (im Browser)" else "Werbeblocker (im Browser)")
        popup.menu.add(0, 12, 12, if (vpnActive) "✓ System-VPN Ad-Block aktiv" else "System-VPN Ad-Block starten")
        popup.menu.add(0, 13, 13, "🎨 Design wählen…")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                99 -> {
                    currentTab?.uaMode = UserAgentMode.FIREFOX_DESKTOP
                    applyTabUserAgent(currentTab ?: return@setOnMenuItemClickListener true, reload = true)
                    Toast.makeText(this, "Firefox User-Agent aktiv", Toast.LENGTH_SHORT).show()
                    true
                }
                1 -> { showFavoritesDialog(); true }
                2 -> { showHistoryDialog(); true }
                3 -> { showDownloadsDialog(); true }
                4 -> { showExtensionsDialog(); true }
                5 -> { showTabSwitcher(); true }
                6 -> { showDevToolsDialog(); true }
                7 -> { openCurrentUrlWithExternalApp(); true }
                8 -> { currentWebViewOrNull()?.let { if (it.canGoForward()) it.goForward() }; true }
                9 -> { showSettingsDialog(); true }
                10 -> { toggleDesktopMode(); true }
                11 -> { toggleInPageAdBlock(); true }
                12 -> { toggleSystemVpn(); true }
                13 -> { showThemePicker(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---------- Extensions Popup (Toolbar Button 🧩) ----------

    private fun showExtensionsPopup() {
        val popupBinding = DialogExtensionsPopupBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(popupBinding.root)
            .create()

        val currentUrl = currentWebViewOrNull()?.url.orEmpty()
        val xpiList = XpiManager.getInstalledExtensions(this)
        val builtinList = ExtensionManager.all()

        popupBinding.btnManageAllAddons.setOnClickListener {
            dialog.dismiss()
            showExtensionsDialog()
        }

        popupBinding.btnPopupBrowseAddons.setOnClickListener {
            dialog.dismiss()
            openNewTab("https://addons.mozilla.org", UserAgentMode.FIREFOX_DESKTOP)
        }

        popupBinding.btnPopupInstallXpi.setOnClickListener {
            dialog.dismiss()
            xpiFilePickerLauncher.launch("*/*")
        }

        fun populateList() {
            popupBinding.popupExtensionsList.removeAllViews()
            val hasItems = xpiList.isNotEmpty() || builtinList.isNotEmpty()
            popupBinding.textNoActiveExtensions.visibility = if (hasItems) View.GONE else View.VISIBLE

            // 1. Installed XPI extensions (e.g. Proton Pass, AdGuard)
            xpiList.forEach { ext ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 12, 8, 12)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        // When tapped: open its popup (like Proton Pass login) or options (like AdGuard settings)
                        val targetPage = ext.popupPagePath ?: ext.optionsPagePath
                        if (targetPage != null) {
                            showExtensionHtmlPage(ext.name, targetPage, ext)
                        } else {
                            showExtensionDetailDialog(ext)
                        }
                    }
                }

                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_extension)
                    layoutParams = LinearLayout.LayoutParams(26, 26).apply { marginEnd = 12 }
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleView = TextView(this).apply {
                    text = "${ext.name} (v${ext.version})"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                }

                val subView = TextView(this).apply {
                    val activeOnPage = XpiManager.matchesUrl(ext, currentUrl)
                    text = when {
                        !ext.popupPagePath.isNullOrBlank() -> "🚀 Popup / Login verfügbar"
                        !ext.optionsPagePath.isNullOrBlank() -> "⚙️ Einstellungen / Filter verfügbar"
                        activeOnPage -> "● Auf dieser Seite aktiv"
                        else -> "Inaktiv"
                    }
                    textSize = 11f
                    setTextColor(getColor(if (activeOnPage || !ext.popupPagePath.isNullOrBlank()) android.R.color.holo_green_dark else android.R.color.darker_gray))
                }

                textCol.addView(titleView)
                textCol.addView(subView)

                // Gear button to open detailed options
                val gearBtn = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_preferences)
                    setBackgroundResource(0)
                    setPadding(10, 10, 10, 10)
                    setOnClickListener {
                        dialog.dismiss()
                        showExtensionDetailDialog(ext)
                    }
                }

                val switch = SwitchMaterial(this).apply {
                    isChecked = ext.enabled
                    setOnCheckedChangeListener { _, isChecked ->
                        XpiManager.setEnabled(this@MainActivity, ext.id, isChecked)
                        currentWebViewOrNull()?.reload()
                    }
                }

                row.addView(icon)
                row.addView(textCol)
                row.addView(gearBtn)
                row.addView(switch)
                popupBinding.popupExtensionsList.addView(row)
            }

            // 2. Builtin scripts
            builtinList.forEach { ext ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 10, 8, 10)
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleView = TextView(this).apply {
                    text = ext.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                }

                val subView = TextView(this).apply {
                    text = ext.description
                    textSize = 11f
                    setTextColor(getColor(android.R.color.darker_gray))
                }

                textCol.addView(titleView)
                textCol.addView(subView)

                val switch = SwitchMaterial(this).apply {
                    isChecked = ext.enabled
                    setOnCheckedChangeListener { _, isChecked ->
                        ExtensionManager.setEnabled(this@MainActivity, ext.id, isChecked)
                        currentWebViewOrNull()?.reload()
                    }
                }

                row.addView(textCol)
                row.addView(switch)
                popupBinding.popupExtensionsList.addView(row)
            }
        }

        populateList()
        dialog.show()
    }

    // ---------- Firefox-style Extension Details & Options Dialog ----------

    private fun showExtensionDetailDialog(ext: InstalledXpiExtension) {
        val detailBinding = DialogExtensionDetailsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(detailBinding.root)
            .setPositiveButton("Schließen", null)
            .create()

        detailBinding.detailExtName.text = ext.name
        detailBinding.detailExtVersion.text = "v${ext.version}"
        detailBinding.detailExtAuthor.text = "von ${ext.author}"
        detailBinding.detailExtDescription.text = ext.description.ifBlank { "Firefox WebExtension Add-on" }
        detailBinding.detailSwitchEnabled.isChecked = ext.enabled

        detailBinding.detailSwitchEnabled.setOnCheckedChangeListener { _, isChecked ->
            XpiManager.setEnabled(this, ext.id, isChecked)
            currentWebViewOrNull()?.reload()
        }

        // Popup Button (e.g. Proton Pass Login / Vault)
        val extDir = File(filesDir, "xpi_extensions/${ext.id}")
        val popupFile = ext.popupPagePath?.let { File(it) } ?: File(extDir, "popup.html").takeIf { it.exists() }
        if (popupFile != null && popupFile.exists()) {
            detailBinding.btnOpenExtPopup.visibility = View.VISIBLE
            detailBinding.btnOpenExtPopup.setOnClickListener {
                showExtensionHtmlPage("${ext.name} (Popup)", popupFile.absolutePath, ext)
            }
        } else {
            detailBinding.btnOpenExtPopup.visibility = View.GONE
        }

        // Options UI button (e.g. AdGuard options / filter rules)
        val optionsFile = ext.optionsPagePath?.let { File(it) } ?: File(extDir, "options.html").takeIf { it.exists() } ?: File(extDir, "settings.html").takeIf { it.exists() }
        if (optionsFile != null && optionsFile.exists()) {
            detailBinding.btnOpenExtOptionsUi.visibility = View.VISIBLE
            detailBinding.btnOpenExtOptionsUi.setOnClickListener {
                showExtensionHtmlPage("${ext.name} (Einstellungen)", optionsFile.absolutePath, ext)
            }
        } else {
            detailBinding.btnOpenExtOptionsUi.visibility = View.GONE
        }

        // Matches / Website Access
        val matchesText = if (ext.matches.any { it == "*" || it == "<all_urls>" }) {
            "● Auf allen Webseiten aktiv"
        } else {
            "● Aktiv auf: ${ext.matches.joinToString(", ")}"
        }
        detailBinding.detailExtMatches.text = matchesText

        // Permissions
        if (ext.permissions.isNotEmpty()) {
            detailBinding.detailExtPermissions.text = ext.permissions.joinToString("\n") { "• $it" }
        } else {
            detailBinding.detailExtPermissions.text = "Keine speziellen Berechtigungen erforderlich"
        }

        // Uninstall
        detailBinding.btnDetailUninstall.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Erweiterung deinstallieren")
                .setMessage("Möchten Sie \"${ext.name}\" wirklich entfernen?")
                .setPositiveButton("Deinstallieren") { _, _ ->
                    XpiManager.uninstall(this, ext.id)
                    dialog.dismiss()
                    Toast.makeText(this, "Erweiterung \"${ext.name}\" deinstalliert", Toast.LENGTH_SHORT).show()
                    currentWebViewOrNull()?.reload()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        dialog.show()
    }

    // ---------- Interactive Extension WebView Runner (Proton Pass / AdGuard) ----------

    private fun showExtensionHtmlPage(title: String, filePath: String, ext: InstalledXpiExtension) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Erweiterungs-Datei nicht gefunden: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        val pageBinding = DialogExtensionWebviewBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(pageBinding.root)
            .create()

        pageBinding.textExtWebviewTitle.text = title
        pageBinding.btnCloseExtWebview.setOnClickListener {
            dialog.dismiss()
            currentWebViewOrNull()?.reload()
        }

        val extWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            // Bridge providing WebExtensions API (browser.storage.local, browser.runtime, etc.)
            addJavascriptInterface(WebExtensionBridge(this@MainActivity, ext) { openUrl ->
                runOnUiThread {
                    dialog.dismiss()
                    openNewTab(openUrl)
                }
            }, "AndroidWebExt")

            val currentPageUrl = currentWebViewOrNull()?.url.orEmpty()
            val baseUrl = "file://${file.parentFile?.absolutePath}/"
            val bridgeJs = WebExtensionBridge.getBridgeScript(
                this@MainActivity,
                ext,
                currentPageUrl,
                baseUrl
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageBinding.progressExtWebview.visibility = View.VISIBLE
                    view.evaluateJavascript(bridgeJs, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    pageBinding.progressExtWebview.visibility = View.GONE
                    view.evaluateJavascript(bridgeJs, null)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    DevToolsManager.addConsoleMessage(
                        ConsoleMessageItem(
                            level = "[Ext:${ext.name}] " + consoleMessage.messageLevel().name,
                            message = consoleMessage.message(),
                            source = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                        )
                    )
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    pageBinding.progressExtWebview.progress = newProgress
                }
            }

            loadUrl("file://${file.absolutePath}")
        }

        pageBinding.extWebviewContainer.addView(
            extWebView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        dialog.show()
    }

    // ---------- Favorites dialog ----------

    private fun showFavoritesDialog() {
        val dialogBinding = DialogFavoritesBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.favorites_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Schließen", null)
            .create()

        fun refreshList() {
            val list = FavoritesManager.getAll(this)
            dialogBinding.favoritesListContainer.removeAllViews()
            dialogBinding.textFavoritesEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

            list.forEach { fav ->
                val itemView = createFavoriteItemRow(
                    fav,
                    onClick = {
                        dialog.dismiss()
                        val tab = currentTabOrNull()
                        if (tab != null) {
                            applyTabUserAgent(tab, fav.url, reload = false)
                            tab.webView.loadUrl(fav.url)
                        } else {
                            openNewTab(fav.url)
                        }
                    },
                    onDelete = {
                        FavoritesManager.remove(this, fav.id)
                        refreshList()
                        updateFavoriteButtonIcon()
                        Toast.makeText(this, R.string.favorites_removed, Toast.LENGTH_SHORT).show()
                    }
                )
                dialogBinding.favoritesListContainer.addView(itemView)
            }
        }

        dialogBinding.btnSaveCurrentFavorite.setOnClickListener {
            val currentTab = currentTabOrNull()
            val url = currentTab?.webView?.url ?: currentTab?.url.orEmpty()
            val title = currentTab?.webView?.title ?: currentTab?.title.orEmpty()
            if (url.isNotBlank() && !url.startsWith("about:")) {
                FavoritesManager.add(this, title, url)
                refreshList()
                updateFavoriteButtonIcon()
                Toast.makeText(this, R.string.favorites_added, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Keine gültige Seite zum Speichern", Toast.LENGTH_SHORT).show()
            }
        }

        refreshList()
        dialog.show()
    }

    private fun createFavoriteItemRow(
        fav: FavoriteItem,
        onClick: () -> Unit,
        onDelete: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = fav.title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val urlView = TextView(this).apply {
            text = fav.url
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        textContainer.addView(titleView)
        textContainer.addView(urlView)

        val deleteButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setBackgroundResource(0)
            setPadding(12, 12, 12, 12)
            setOnClickListener { onDelete() }
        }

        row.addView(textContainer)
        row.addView(deleteButton)
        return row
    }

    // ---------- History dialog ----------

    private fun showHistoryDialog() {
        val dialogBinding = DialogHistoryBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Schließen", null)
            .create()

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun refreshList() {
            val list = HistoryManager.getAll(this)
            dialogBinding.historyListContainer.removeAllViews()
            dialogBinding.textHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

            list.forEach { item ->
                val itemView = createHistoryItemRow(
                    item,
                    dateFormat,
                    onClick = {
                        dialog.dismiss()
                        val tab = currentTabOrNull()
                        if (tab != null) {
                            applyTabUserAgent(tab, item.url, reload = false)
                            tab.webView.loadUrl(item.url)
                        } else {
                            openNewTab(item.url)
                        }
                    },
                    onDelete = {
                        HistoryManager.remove(this, item.id)
                        refreshList()
                    }
                )
                dialogBinding.historyListContainer.addView(itemView)
            }
        }

        dialogBinding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.history_clear)
                .setMessage("Möchten Sie den gesamten Verlauf wirklich leeren?")
                .setPositiveButton("Löschen") { _, _ ->
                    HistoryManager.clear(this)
                    refreshList()
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        refreshList()
        dialog.show()
    }

    private fun createHistoryItemRow(
        item: HistoryItem,
        dateFormat: SimpleDateFormat,
        onClick: () -> Unit,
        onDelete: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = item.title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val urlView = TextView(this).apply {
            text = item.url
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val dateView = TextView(this).apply {
            text = dateFormat.format(Date(item.timestamp))
            textSize = 10f
            setTextColor(getColor(android.R.color.darker_gray))
        }

        textContainer.addView(titleView)
        textContainer.addView(urlView)
        textContainer.addView(dateView)

        val deleteButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setBackgroundResource(0)
            setPadding(12, 12, 12, 12)
            setOnClickListener { onDelete() }
        }

        row.addView(textContainer)
        row.addView(deleteButton)
        return row
    }

    // ---------- Downloads dialog ----------

    private fun showDownloadsDialog() {
        val dialogBinding = DialogDownloadsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.downloads_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Schließen", null)
            .create()

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun refreshList() {
            val list = DownloadsManager.getAll(this)
            dialogBinding.downloadsListContainer.removeAllViews()
            dialogBinding.textDownloadsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

            list.forEach { record ->
                val itemView = createDownloadItemRow(record, dateFormat) {
                    DownloadsManager.removeRecord(this, record.id)
                    refreshList()
                }
                dialogBinding.downloadsListContainer.addView(itemView)
            }
        }

        dialogBinding.btnOpenDownloadsFolder.setOnClickListener {
            DownloadsManager.openSystemDownloads(this)
        }

        dialogBinding.btnClearDownloads.setOnClickListener {
            DownloadsManager.clear(this)
            refreshList()
        }

        refreshList()
        dialog.show()
    }

    private fun createDownloadItemRow(
        record: DownloadRecord,
        dateFormat: SimpleDateFormat,
        onDelete: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { DownloadsManager.openSystemDownloads(context) }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = record.fileName
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val dateView = TextView(this).apply {
            text = dateFormat.format(Date(record.timestamp))
            textSize = 11f
            setTextColor(getColor(android.R.color.darker_gray))
        }

        textContainer.addView(nameView)
        textContainer.addView(dateView)

        val deleteButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setBackgroundResource(0)
            setPadding(12, 12, 12, 12)
            setOnClickListener { onDelete() }
        }

        row.addView(textContainer)
        row.addView(deleteButton)
        return row
    }

    // ---------- XPI Extension Installation (.xpi Dateien) ----------

    private fun showXpiDownloadOptionsDialog(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        fileName: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Firefox-Erweiterung (.xpi)")
            .setMessage("Datei: $fileName\n\nMöchten Sie diese Firefox-Erweiterung direkt in Gravity installieren oder mit einer externen App öffnen?")
            .setPositiveButton("Im Browser installieren") { _, _ ->
                installXpiFromUrl(url, userAgent, fileName)
            }
            .setNeutralButton("Öffnen mit…") { _, _ ->
                DownloadsManager.enqueueDownload(this, url, userAgent, contentDisposition, mimetype)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching {
                    startActivity(Intent.createChooser(intent, "Erweiterung öffnen mit…"))
                }
            }
            .setNegativeButton("Nur herunterladen") { _, _ ->
                DownloadsManager.enqueueDownload(this, url, userAgent, contentDisposition, mimetype)
            }
            .show()
    }

    private fun installXpiFromUrl(url: String, userAgent: String?, fileName: String) {
        Toast.makeText(this, "Erweiterung wird heruntergeladen und installiert…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                if (!userAgent.isNullOrBlank()) {
                    connection.setRequestProperty("User-Agent", userAgent)
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }
                connection.connect()
                val result = XpiManager.installFromStream(this, connection.inputStream, fileName)
                runOnUiThread {
                    result.onSuccess { ext ->
                        Toast.makeText(this, "Erweiterung \"${ext.name}\" erfolgreich installiert!", Toast.LENGTH_LONG).show()
                        currentWebViewOrNull()?.reload()
                    }.onFailure { err ->
                        Toast.makeText(this, "Installationsfehler: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { err ->
                runOnUiThread {
                    Toast.makeText(this, "Download-Fehler: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installXpiFromUri(uri: Uri) {
        Toast.makeText(this, "Erweiterung wird installiert…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val result = XpiManager.installFromStream(this, stream, "Firefox-Erweiterung")
                    runOnUiThread {
                        result.onSuccess { ext ->
                            Toast.makeText(this, "Erweiterung \"${ext.name}\" erfolgreich installiert!", Toast.LENGTH_LONG).show()
                            currentWebViewOrNull()?.reload()
                        }.onFailure { err ->
                            Toast.makeText(this, "Installationsfehler: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } ?: error("Datei konnte nicht geöffnet werden.")
            }.onFailure { err ->
                runOnUiThread {
                    Toast.makeText(this, "Fehler beim Lesen: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- Extensions dialog (Firefox Add-ons Manager) ----------

    private fun showExtensionsDialog() {
        val dialogBinding = DialogExtensionsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.extensions_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Fertig") { _, _ -> currentWebViewOrNull()?.reload() }
            .create()

        // Mozilla Add-ons launcher
        dialogBinding.btnOpenFirefoxAddonsExt.setOnClickListener {
            dialog.dismiss()
            openNewTab("https://addons.mozilla.org", UserAgentMode.FIREFOX_DESKTOP)
            Toast.makeText(this, "Mozilla Add-ons geöffnet", Toast.LENGTH_SHORT).show()
        }

        // Install XPI from storage button
        dialogBinding.btnInstallXpiFile.setOnClickListener {
            xpiFilePickerLauncher.launch("*/*")
        }

        fun refreshXpiList() {
            val xpiList = XpiManager.getInstalledExtensions(this)
            dialogBinding.xpiExtensionsListContainer.removeAllViews()
            dialogBinding.textNoXpiInstalled.visibility = if (xpiList.isEmpty()) View.VISIBLE else View.GONE

            xpiList.forEach { xpi ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 12, 8, 12)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showExtensionDetailDialog(xpi)
                    }
                }

                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_extension)
                    layoutParams = LinearLayout.LayoutParams(28, 28).apply { marginEnd = 12 }
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameView = TextView(this).apply {
                    text = "${xpi.name} (v${xpi.version})"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                }

                val descView = TextView(this).apply {
                    text = xpi.description.ifBlank { "Firefox WebExtension" }
                    textSize = 12f
                    setTextColor(getColor(android.R.color.darker_gray))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                textCol.addView(nameView)
                textCol.addView(descView)

                val switch = SwitchMaterial(this).apply {
                    isChecked = xpi.enabled
                    setOnCheckedChangeListener { _, isChecked ->
                        XpiManager.setEnabled(this@MainActivity, xpi.id, isChecked)
                        currentWebViewOrNull()?.reload()
                    }
                }

                row.addView(icon)
                row.addView(textCol)
                row.addView(switch)
                dialogBinding.xpiExtensionsListContainer.addView(row)
            }
        }
        refreshXpiList()

        // Built-in scripts
        val extensions = ExtensionManager.all()
        dialogBinding.extensionsListContainer.removeAllViews()
        extensions.forEach { ext ->
            val switch = SwitchMaterial(this).apply {
                text = "${ext.name}\n${ext.description}"
                isChecked = ext.enabled
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, isChecked ->
                    ExtensionManager.setEnabled(this@MainActivity, ext.id, isChecked)
                    currentWebViewOrNull()?.reload()
                }
            }
            dialogBinding.extensionsListContainer.addView(switch)
        }

        dialog.show()
    }

    // ---------- Developer Tools dialog ----------

    private fun showDevToolsDialog() {
        val dialogBinding = DialogDevtoolsBinding.inflate(layoutInflater)
        val currentTab = currentTabOrNull()
        val currentWebView = currentTab?.webView

        // User-Agent Switcher
        val currentUaMode = currentTab?.uaMode ?: UserAgentMode.AUTO
        when (currentUaMode) {
            UserAgentMode.AUTO -> dialogBinding.rbUaAuto.isChecked = true
            UserAgentMode.FIREFOX_DESKTOP -> dialogBinding.rbUaFirefox.isChecked = true
            UserAgentMode.FIREFOX_MOBILE -> dialogBinding.rbUaFirefoxMobile.isChecked = true
            UserAgentMode.DEFAULT_MOBILE -> dialogBinding.rbUaMobile.isChecked = true
            UserAgentMode.DESKTOP -> dialogBinding.rbUaDesktop.isChecked = true
        }

        dialogBinding.btnApplyUa.setOnClickListener {
            val selectedMode = when (dialogBinding.rgUserAgent.checkedRadioButtonId) {
                dialogBinding.rbUaFirefox.id -> UserAgentMode.FIREFOX_DESKTOP
                dialogBinding.rbUaFirefoxMobile.id -> UserAgentMode.FIREFOX_MOBILE
                dialogBinding.rbUaMobile.id -> UserAgentMode.DEFAULT_MOBILE
                dialogBinding.rbUaDesktop.id -> UserAgentMode.DESKTOP
                else -> UserAgentMode.AUTO
            }
            if (currentTab != null) {
                currentTab.uaMode = selectedMode
                applyTabUserAgent(currentTab, reload = true)
                Toast.makeText(this, "User-Agent auf ${selectedMode.displayName} gesetzt", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnOpenFirefoxAddons.setOnClickListener {
            openNewTab("https://addons.mozilla.org", UserAgentMode.FIREFOX_DESKTOP)
        }

        // Open with external app
        dialogBinding.btnOpenWith.setOnClickListener {
            openCurrentUrlWithExternalApp()
        }

        // Install XPI file
        dialogBinding.btnInstallXpi.setOnClickListener {
            xpiFilePickerLauncher.launch("*/*")
        }

        // Console Logs
        fun updateConsoleView() {
            val logs = DevToolsManager.getLogs()
            if (logs.isEmpty()) {
                dialogBinding.textConsoleLogs.text = getString(R.string.devtools_no_logs)
            } else {
                val sb = java.lang.StringBuilder()
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                logs.take(50).forEach { item ->
                    sb.append("[${timeFormat.format(Date(item.timestamp))}] [${item.level}] ${item.message}\n(${item.source})\n\n")
                }
                dialogBinding.textConsoleLogs.text = sb.toString()
            }
        }
        updateConsoleView()

        dialogBinding.btnClearConsole.setOnClickListener {
            DevToolsManager.clearLogs()
            updateConsoleView()
        }

        // Execute JavaScript
        dialogBinding.btnRunJs.setOnClickListener {
            val code = dialogBinding.editJsCommand.text.toString().trim()
            if (code.isNotBlank() && currentWebView != null) {
                currentWebView.evaluateJavascript(code) { result ->
                    dialogBinding.textJsResult.visibility = View.VISIBLE
                    dialogBinding.textJsResult.text = "Ergebnis: $result"
                }
            }
        }

        // View Source
        dialogBinding.btnViewHtmlSource.setOnClickListener {
            currentWebView?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                val unescaped = html?.trim('\"')
                    ?.replace("\\u003C", "<")
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"")
                    ?: "Kein Quelltext verfügbar"
                val viewerBinding = DialogSourceViewerBinding.inflate(layoutInflater)
                viewerBinding.textHtmlSource.text = unescaped
                AlertDialog.Builder(this)
                    .setTitle("HTML Quelltext")
                    .setView(viewerBinding.root)
                    .setPositiveButton("Schließen", null)
                    .show()
            }
        }

        // Page info
        val currentUrl = currentWebView?.url.orEmpty()
        val cookies = CookieManager.getInstance().getCookie(currentUrl)
        val activeUa = currentWebView?.settings?.userAgentString ?: "Standard"
        dialogBinding.textPageInfo.text = "URL: $currentUrl\n\nUser-Agent:\n$activeUa\n\nCookies aktiv: ${!cookies.isNullOrBlank()}"

        AlertDialog.Builder(this)
            .setTitle(R.string.devtools_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Schließen", null)
            .show()
    }

    // ---------- Settings dialog ----------

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)

        // Desktop site toggle
        dialogBinding.switchDefaultDesktop.isChecked = SettingsManager.isDefaultDesktopSite(this)
        dialogBinding.switchDefaultDesktop.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setDefaultDesktopSite(this, isChecked)
        }

        // Manage Extensions link
        dialogBinding.containerManageExtensions.setOnClickListener {
            showExtensionsDialog()
        }

        // Search engine
        fun updateSearchEngineText() {
            dialogBinding.textSearchEngineValue.text = SettingsManager.getSearchEngine(this).displayName
        }
        updateSearchEngineText()
        dialogBinding.containerSearchEngine.setOnClickListener {
            val engines = SearchEngine.values()
            val names = engines.map { it.displayName }.toTypedArray()
            val current = engines.indexOf(SettingsManager.getSearchEngine(this))
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_search_engine)
                .setSingleChoiceItems(names, current) { d, which ->
                    SettingsManager.setSearchEngine(this, engines[which])
                    updateSearchEngineText()
                    d.dismiss()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        // Home page
        fun updateHomePageText() {
            dialogBinding.textHomePageValue.text = SettingsManager.getHomePage(this)
        }
        updateHomePageText()
        dialogBinding.containerHomePage.setOnClickListener {
            val input = EditText(this).apply {
                setText(SettingsManager.getHomePage(this@MainActivity))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSelection(text.length)
            }
            val container = FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_home_page)
                .setView(container)
                .setPositiveButton("Speichern") { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.isNotBlank()) {
                        val validUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
                        SettingsManager.setHomePage(this, validUrl)
                        updateHomePageText()
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        // JavaScript
        dialogBinding.switchJavaScript.isChecked = SettingsManager.isJavaScriptEnabled(this)
        dialogBinding.switchJavaScript.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setJavaScriptEnabled(this, isChecked)
            tabs.forEach { it.webView.settings.javaScriptEnabled = isChecked }
        }

        // DOM Storage
        dialogBinding.switchDomStorage.isChecked = SettingsManager.isDomStorageEnabled(this)
        dialogBinding.switchDomStorage.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setDomStorageEnabled(this, isChecked)
            tabs.forEach { it.webView.settings.domStorageEnabled = isChecked }
        }

        // Clear browser data
        dialogBinding.containerClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_data)
                .setMessage(R.string.settings_clear_data_confirm)
                .setPositiveButton("Löschen") { _, _ ->
                    tabs.forEach { tab ->
                        tab.webView.clearCache(true)
                        tab.webView.clearHistory()
                    }
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    HistoryManager.clear(this)
                    updateFavoriteButtonIcon()
                    Toast.makeText(this, R.string.settings_clear_data_success, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun toggleInPageAdBlock() {
        AdBlockManager.isEnabled = !AdBlockManager.isEnabled
        currentWebViewOrNull()?.reload()
    }

    private fun toggleSystemVpn() {
        if (vpnActive) {
            startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
            vpnActive = false
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN_PERMISSION)
        } else {
            onVpnPermissionGranted()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            onVpnPermissionGranted()
        }
    }

    private fun onVpnPermissionGranted() {
        startService(Intent(this, AdBlockVpnService::class.java))
        vpnActive = true
    }

    private fun showThemePicker() {
        val themes = AppTheme.values()
        val names = themes.map { it.displayName }.toTypedArray()
        val current = ThemeManager.getSavedTheme(this)
        AlertDialog.Builder(this)
            .setTitle("Design wählen")
            .setSingleChoiceItems(names, themes.indexOf(current)) { dialog, which ->
                ThemeManager.selectTheme(this, themes[which])
                dialog.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 100
    }
}
