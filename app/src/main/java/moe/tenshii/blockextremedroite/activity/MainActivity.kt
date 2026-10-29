package moe.tenshii.blockextremedroite.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.transition.TransitionManager
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import moe.tenshii.blockextremedroite.Account
import moe.tenshii.blockextremedroite.AccountRepository
import moe.tenshii.blockextremedroite.BlockCounter
import moe.tenshii.blockextremedroite.BlockService
import moe.tenshii.blockextremedroite.BlockStatus
import moe.tenshii.blockextremedroite.BuildConfig
import moe.tenshii.blockextremedroite.CounterSnapshot
import moe.tenshii.blockextremedroite.Http
import moe.tenshii.blockextremedroite.Pacing
import moe.tenshii.blockextremedroite.Platform
import moe.tenshii.blockextremedroite.ProgressStore
import moe.tenshii.blockextremedroite.R
import moe.tenshii.blockextremedroite.Release
import moe.tenshii.blockextremedroite.RunMonitor
import moe.tenshii.blockextremedroite.RunState
import moe.tenshii.blockextremedroite.SessionRequiredException
import moe.tenshii.blockextremedroite.SettingsStore
import moe.tenshii.blockextremedroite.SiteCategory
import moe.tenshii.blockextremedroite.SiteSession
import moe.tenshii.blockextremedroite.StopReason
import moe.tenshii.blockextremedroite.Updater
import moe.tenshii.blockextremedroite.databinding.ActivityMainBinding
import moe.tenshii.blockextremedroite.databinding.ItemCategoryChipBinding
import moe.tenshii.blockextremedroite.pluralQuantity
import java.io.File
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.reflect.KMutableProperty0

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AccountRepository
    private lateinit var progress: ProgressStore
    private lateinit var settings: SettingsStore
    private lateinit var counter: BlockCounter
    private lateinit var updater: Updater
    private lateinit var session: SiteSession
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var accountsTab: AccountsTab
    @IdRes private var tab = R.id.tabBlock
    /** Leaving the Comptes or Réglages tab with Back returns to Bloquer before leaving the app. */
    private val backToBlockTab = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.bottomNav.selectedItemId = R.id.tabBlock
        }
    }

    private var accounts: List<Account> = emptyList()
        set(value) {
            field = value
            accountsTab.accounts = value
        }
    private var listLoadFailed = false
    private var refreshing = false
    /** Set when the site wants a new anti-bot check before it sends more of the list. */
    private var needsVerification = false
    /** Set when "Lancer le blocage" opened the anti-bot check, so the run starts once it passes. */
    private var startAfterVerification = false
    private var counts: CounterSnapshot? = null
    private var update: Release? = null
    private var checkingUpdate = false
    /** Download progress in percent, null when no download is running. */
    private var updateProgress: Int? = null
    /** Set when the user is sent to grant the install permission, so the update carries on once they're back. */
    private var updateAfterPermission = false
    private var wasRunning = false
    private var appInstalled = true
    private val numbers = NumberFormat.getIntegerInstance()
    private val runListener: (RunState) -> Unit = { state ->
        if (wasRunning && !state.running) {
            // Reports are sent in the background, so give them a moment before reading the counter back.
            binding.root.postDelayed(::loadCounts, COUNTS_RELOAD_DELAY_MS)
            // The run may have added the deck's next batches to the saved list.
            reloadSaved()
        }
        wasRunning = state.running
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // The bottom bar pads itself above the navigation bar.
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }

        repository = AccountRepository(this)
        progress = ProgressStore(this)
        settings = SettingsStore(this)
        counter = BlockCounter.get(this)
        updater = Updater(this)
        session = SiteSession(this)
        accountsTab = AccountsTab(this, binding.accountsTab, savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backToBlockTab)
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        val savedTab = savedInstanceState?.getInt(KEY_TAB) ?: R.id.tabBlock
        binding.bottomNav.selectedItemId = savedTab
        showTab(savedTab)
        supportFragmentManager.setFragmentResultListener(VerifyDialog.REQUEST_KEY, this) { _, _ ->
            needsVerification = false
            counter.flush()
            if (startAfterVerification) {
                startAfterVerification = false
                toggleRun()
            } else {
                refreshList(manual = true)
            }
        }

        binding.enableService.setOnClickListener { showDisclosure() }
        binding.installApp.setOnClickListener { openStorePage(settings.platform) }
        binding.openRepository.setOnClickListener { openRepository() }
        binding.startStop.setOnClickListener { toggleRun() }
        binding.resetProgress.setOnClickListener { confirmReset() }
        binding.updateInstall.setOnClickListener { startUpdate() }
        binding.updateNotes.setOnClickListener { showUpdateNotes() }
        binding.checkUpdate.setOnClickListener { checkForUpdate(manual = true) }
        binding.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        bindSection(binding.listHeader, binding.listChevron, binding.listBody, settings::listExpanded)
        setUpPacing()
        loadList()
        loadCounts()
        counter.flush()
        update = updater.available
        checkForUpdate(manual = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
        accountsTab.saveState(outState)
    }

    private fun showTab(@IdRes id: Int) {
        tab = id
        binding.homeTab.isVisible = id == R.id.tabBlock
        binding.accountsTab.root.isVisible = id == R.id.tabAccounts
        binding.settingsTab.isVisible = id == R.id.tabSettings
        backToBlockTab.isEnabled = id != R.id.tabBlock
        if (id == R.id.tabAccounts) accountsTab.render()
    }

    override fun onStart() {
        appInstalled = BlockService.isInstalled(this, settings.platform)
        super.onStart()
        RunMonitor.addListener(runListener)
        if (updateAfterPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            updateAfterPermission = false
            startUpdate()
        }
    }

    override fun onStop() {
        RunMonitor.removeListener(runListener)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindSection(header: View, chevron: View, body: View, expanded: KMutableProperty0<Boolean>) {
        fun show(animate: Boolean) {
            val open = expanded.get()
            if (animate) TransitionManager.beginDelayedTransition(binding.content)
            body.isVisible = open
            val rotation = if (open) 180f else 0f
            if (animate) chevron.animate().rotation(rotation) else chevron.rotation = rotation
            ViewCompat.setStateDescription(header, getString(if (open) R.string.section_expanded else R.string.section_collapsed))
        }
        header.setOnClickListener {
            expanded.set(!expanded.get())
            show(animate = true)
        }
        show(animate = false)
    }

    private fun setUpPacing() {
        val pacing = settings.pacing
        binding.delaySlider.values = listOf(
            pacing.minDelaySeconds.coerceIn(SettingsStore.DELAY_RANGE_MIN, SettingsStore.DELAY_RANGE_MAX).toFloat(),
            pacing.maxDelaySeconds.coerceIn(SettingsStore.DELAY_RANGE_MIN, SettingsStore.DELAY_RANGE_MAX).toFloat(),
        )
        binding.capSlider.valueFrom = SettingsStore.CAP_MIN.toFloat()
        binding.capSlider.valueTo = SettingsStore.CAP_NO_LIMIT_POSITION.toFloat()
        binding.capSlider.stepSize = SettingsStore.CAP_STEP.toFloat()
        binding.capSlider.value =
            (pacing.sessionCap?.coerceIn(SettingsStore.CAP_MIN, SettingsStore.CAP_MAX) ?: SettingsStore.CAP_NO_LIMIT_POSITION).toFloat()
        binding.delaySlider.addOnChangeListener { _, _, _ -> savePacing() }
        binding.capSlider.addOnChangeListener { _, _, _ -> savePacing() }
        renderPacing()
    }

    private fun savePacing() {
        val (min, max) = binding.delaySlider.values.map { it.toInt() }.sorted()
        val cap = binding.capSlider.value.toInt().takeIf { it <= SettingsStore.CAP_MAX }
        settings.pacing = Pacing(min, max, cap)
        renderPacing()
    }

    private fun renderPacing() {
        val pacing = settings.pacing
        binding.delayLabel.text = when {
            pacing.maxDelaySeconds == 0 -> getString(R.string.pacing_no_pause)
            pacing.minDelaySeconds == pacing.maxDelaySeconds -> getString(R.string.pacing_delay_fixed, pacing.maxDelaySeconds)
            else -> getString(R.string.pacing_delay, pacing.minDelaySeconds, pacing.maxDelaySeconds)
        }
        binding.capLabel.text = pacing.sessionCap?.let { getString(R.string.pacing_cap, it) } ?: getString(R.string.pacing_no_limit)
    }

    /** Shows the saved list, then tops it up from the site's deck. Only an empty list opens the anti-bot check unasked. */
    private fun loadList() {
        refreshing = true
        executor.execute {
            val saved = repository.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                accounts = saved
                buildFilterChips()
                render()
                when {
                    session.current != null -> topUp()
                    saved.isEmpty() -> refreshList(manual = true)
                    else -> {
                        needsVerification = true
                        render()
                    }
                }
            }
        }
    }

    private fun reloadSaved() {
        executor.execute {
            val saved = repository.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                accounts = saved
                buildFilterChips()
                render()
            }
        }
    }

    /**
     * Asks for one batch when fewer profiles than a batch are left to block with a card token, like the site's page
     * does before running dry; a batch also brings tokens for profiles held without one.
     */
    private fun topUp() {
        val platform = settings.platform
        val pending = selectedAccounts().count { platform in it.cardTokens && !progress.status(it.profiles.getValue(platform)).isDone }
        if (pending < AccountRepository.DECK_BATCH && repository.canFetch(platform, settings.deckCategory)) {
            refreshList(manual = false)
        }
    }

    private fun canFetchMore(): Boolean = repository.canFetch(settings.platform, settings.deckCategory)

    private fun loadCounts() {
        executor.execute {
            val result = runCatching { counter.fetch() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { counts = it }
                render()
            }
        }
    }

    /**
     * Asks the deck for one batch on the selected platform. A [manual] one opens the anti-bot check when the
     * site asks for it and reports a failure.
     */
    private fun refreshList(manual: Boolean) {
        if (refreshing) return
        // Without a session the refresh can only fail, and it would first wait behind the counter's requests.
        if (session.current == null) {
            needsVerification = true
            if (manual) VerifyDialog.show(supportFragmentManager)
            render()
            return
        }
        refreshing = true
        render()
        val platform = settings.platform
        val category = settings.deckCategory
        executor.execute {
            val result = runCatching { repository.fetchBatch(platform, category) }
            val saved = repository.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                accounts = saved
                buildFilterChips()
                result.onSuccess {
                    listLoadFailed = false
                    needsVerification = false
                }.onFailure {
                    if (it is SessionRequiredException) {
                        needsVerification = true
                        if (manual) VerifyDialog.show(supportFragmentManager)
                    } else {
                        listLoadFailed = saved.isEmpty()
                        if (manual) snackbar(getString(R.string.list_refresh_failed))
                    }
                }
                render()
            }
        }
    }

    /** Each platform counts what is left to block there in the current selection; category counts follow the selected platform. */
    private fun buildFilterChips() {
        val platform = settings.platform
        binding.platforms.removeAllViews()
        for (option in Platform.entries) {
            val chip = ItemCategoryChipBinding.inflate(layoutInflater, binding.platforms, false).root.apply {
                id = View.generateViewId()
                text = getString(R.string.category_chip, option.label, leftToBlock(option, null))
                isChecked = option == platform
                setOnCheckedChangeListener { _, checked ->
                    if (!checked || settings.platform == option) return@setOnCheckedChangeListener
                    settings.platform = option
                    appInstalled = BlockService.isInstalled(this@MainActivity, option)
                    RunMonitor.update { it.copy(lastStop = null) }
                    buildFilterChips()
                    render()
                    topUp()
                }
            }
            binding.platforms.addView(chip)
        }

        val excluded = settings.excludedCategories
        binding.categories.removeAllViews()
        for (category in SiteCategory.entries) {
            val count = leftToBlock(platform, category)
            // An excluded category stays visible even when empty, or it could never be selected again.
            if (count == 0 && category !in excluded) continue
            val chip = ItemCategoryChipBinding.inflate(layoutInflater, binding.categories, false).root.apply {
                text = getString(R.string.category_chip, getString(category.label), count)
                isChecked = category !in excluded
                setOnCheckedChangeListener { _, checked ->
                    settings.excludedCategories =
                        if (checked) settings.excludedCategories - category else settings.excludedCategories + category
                    buildFilterChips()
                    render()
                    topUp()
                }
            }
            binding.categories.addView(chip)
        }
    }

    private fun selectedAccounts(): List<Account> = settings.select(accounts)

    /**
     * What is left to block on [platform] in [category] (all categories when null), counted like the site's page:
     * its total less the profiles blocked there, or only the profiles held once it has said nothing more is coming.
     * Never less than the profiles held and still to block, as lists saved before the deck can hold more than its total.
     */
    private fun leftToBlock(platform: Platform, category: SiteCategory?): Int {
        val listed = accounts.filter { account ->
            platform in account.profiles && (category == null || account.siteCategory == category)
        }
        val statuses = listed.map { progress.status(it.profiles.getValue(platform)) }
        val held = statuses.count { !it.isDone }
        val total = repository.total(platform, category)
        if (total == null || repository.isExhausted(platform, category)) return held
        return maxOf(total - statuses.count { it.isBlocked }, held)
    }

    private fun render() {
        val state = RunMonitor.state

        val snapshot = counts
        binding.collectiveTotal.isVisible = snapshot != null
        if (snapshot != null) {
            binding.collectiveTotal.text = resources.getQuantityString(
                R.plurals.collective_total, pluralQuantity(snapshot.total), numbers.format(snapshot.total),
            )
        }

        renderUpdate()

        val platform = settings.platform
        binding.appMissing.isVisible = !appInstalled
        binding.appMissingTitle.text = getString(R.string.app_missing_title, platform.label)
        binding.appMissingBody.text = getString(R.string.app_missing_body, platform.label)
        binding.installApp.text = getString(R.string.app_install, platform.label)
        binding.platformNote.isVisible = platform == Platform.YOUTUBE

        binding.serviceStatus.setText(if (state.serviceConnected) R.string.service_on else R.string.service_off)
        binding.enableService.isVisible = !state.serviceConnected

        val listInfo = listInfoText()
        binding.listInfo.text = listInfo
        binding.listInfo.isVisible = listInfo.isNotEmpty()
        for (group in listOf(binding.platforms, binding.categories)) {
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !state.running
        }
        binding.delaySlider.isEnabled = !state.running
        binding.capSlider.isEnabled = !state.running
        binding.resetProgress.isEnabled = !state.running

        val statuses = selectedAccounts().map { progress.status(it.profiles.getValue(platform)) }
        val pending = statuses.count { !it.isDone }
        val blocked = statuses.count { it.isBlocked }
        val unavailable = statuses.count { it == BlockStatus.UNAVAILABLE }
        binding.summary.text = getString(R.string.summary, pending, blocked, unavailable)

        binding.startStop.setText(if (state.running) R.string.stop else R.string.start)
        // Without a session the deck's state is unknown; starting opens the check that settles it.
        val canFetch = canFetchMore() || session.current == null
        binding.startStop.isEnabled = state.running || (appInstalled && state.serviceConnected && (pending > 0 || canFetch))

        val runText = runStatusText(state, statuses.isEmpty(), pending, canFetch)
        binding.runStatus.text = runText
        binding.runStatus.visibility = if (runText == null) View.GONE else View.VISIBLE

        // The list can hold thousands of rows, so it is only rebuilt while its tab is shown.
        if (tab == R.id.tabAccounts) accountsTab.render()
    }

    private fun listInfoText(): String {
        val lines = mutableListOf<String>()
        if (listLoadFailed) lines += getString(R.string.list_load_failed)
        else if (accounts.isEmpty() && refreshing) lines += getString(R.string.list_loading)
        if (needsVerification) lines += getString(R.string.list_needs_verification)
        else if (repository.quotaReachedToday) lines += getString(R.string.list_quota)
        return lines.joinToString("\n")
    }

    private fun renderUpdate() {
        val release = update
        val running = RunMonitor.state.running
        val progress = updateProgress
        binding.updateCard.isVisible = release != null
        if (release != null) {
            binding.updateTitle.text = getString(R.string.update_title, release.version)
            binding.updateBody.text =
                if (running) getString(R.string.update_body_running) else getString(R.string.update_body, BuildConfig.VERSION_NAME)
            binding.updateInstall.text =
                if (progress == null) getString(R.string.update_install) else getString(R.string.update_downloading, progress)
            // Installing replaces the process, which would cut a blocking session short.
            binding.updateInstall.isEnabled = progress == null && !running
            binding.updateNotes.isVisible = release.notes.isNotBlank()
        }
        binding.checkUpdate.isEnabled = !checkingUpdate && progress == null
    }

    private fun runStatusText(state: RunState, noSelection: Boolean, pending: Int, canFetch: Boolean): String? = when {
        state.running && state.current == null -> getString(R.string.run_starting, state.platform.label)
        state.running && state.cap == null ->
            resources.getQuantityString(R.plurals.run_running_unlimited, state.blocked, state.current, state.blocked)
        state.running -> resources.getQuantityString(R.plurals.run_running, state.blocked, state.current, state.blocked, state.cap)
        state.lastStop != null -> when (state.lastStop) {
            StopReason.FINISHED -> getString(R.string.stop_finished)
            StopReason.CAP_REACHED -> resources.getQuantityString(R.plurals.stop_cap, state.lastBlocked, state.lastBlocked)
            StopReason.USER -> resources.getQuantityString(R.plurals.stop_user, state.lastBlocked, state.lastBlocked)
            StopReason.APP_NOT_INSTALLED -> getString(R.string.stop_app_missing, state.platform.label)
            StopReason.LOGGED_OUT -> getString(R.string.stop_logged_out, state.platform.label)
            StopReason.TOO_MANY_FAILURES -> getString(R.string.stop_failures, state.platform.label)
            StopReason.SERVICE_DISCONNECTED -> getString(R.string.stop_service)
        }
        accounts.isEmpty() -> null
        settings.excludedCategories.isNotEmpty() && noSelection && !canFetch -> getString(R.string.no_category)
        pending == 0 && !canFetch -> getString(R.string.nothing_to_block)
        else -> null
    }

    private fun toggleRun() {
        val service = BlockService.instance
        if (RunMonitor.state.running) {
            service?.stop(StopReason.USER)
            return
        }
        if (service == null) {
            showDisclosure()
            return
        }
        // A run fetches the deck's next batches as it goes, which takes a session; the check opens here, when one is needed.
        if (session.current == null) {
            startAfterVerification = true
            VerifyDialog.show(supportFragmentManager)
            return
        }
        val platform = settings.platform
        val queue = selectedAccounts().filter { !progress.status(it.profiles.getValue(platform)).isDone }
        if (queue.isEmpty() && !canFetchMore()) {
            snackbar(getString(R.string.nothing_to_block))
            return
        }
        service.start(platform, queue, settings.pacing)
    }

    private fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        renderUpdate()
        updateExecutor.execute {
            val result = runCatching { updater.check() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checkingUpdate = false
                result.onSuccess {
                    update = it
                    when {
                        !manual -> Unit
                        it == null -> snackbar(getString(R.string.update_none))
                        // The update card lives at the top of Bloquer, out of sight from Réglages where the check is made.
                        tab != R.id.tabBlock -> snackbar(getString(R.string.update_title, it.version), R.string.update_show) {
                            binding.bottomNav.selectedItemId = R.id.tabBlock
                            binding.homeTab.smoothScrollTo(0, 0)
                        }
                    }
                }.onFailure {
                    if (manual) snackbar(getString(R.string.update_check_failed))
                }
                renderUpdate()
            }
        }
    }

    private fun startUpdate() {
        val release = update ?: return
        if (updateProgress != null || RunMonitor.state.running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            askInstallPermission()
            return
        }
        updateProgress = 0
        renderUpdate()
        updateExecutor.execute {
            val result = runCatching {
                updater.download(release) { percent ->
                    runOnUiThread {
                        if (isDestroyed || updateProgress == null) return@runOnUiThread
                        updateProgress = percent
                        renderUpdate()
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updateProgress = null
                result.onSuccess(::installUpdate).onFailure { snackbar(getString(R.string.update_failed)) }
                renderUpdate()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun askInstallPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setPositiveButton(R.string.disclosure_open_settings) { _, _ ->
                updateAfterPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installUpdate(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            snackbar(getString(R.string.update_failed))
        }
    }

    private fun showUpdateNotes() {
        val release = update ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_notes_title, release.version))
            .setMessage(release.notes)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showDisclosure() {
        val message = buildString {
            append(getString(R.string.disclosure_body))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("\n\n")
                append(getString(R.string.disclosure_restricted))
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(message)
            .setPositiveButton(R.string.disclosure_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dialog.setNeutralButton(R.string.disclosure_app_info) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        dialog.show()
    }

    private fun confirmReset() {
        val platform = settings.platform
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_title, platform.label))
            .setMessage(getString(R.string.reset_body, platform.label))
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                progress.clear(platform)
                RunMonitor.update { it.copy(lastStop = null) }
                buildFilterChips()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openRepository() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Http.PROJECT_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            snackbar(Http.PROJECT_URL)
        }
    }

    private fun openStorePage(platform: Platform) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${platform.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${platform.packageName}".toUri()))
        }
    }

    private fun snackbar(message: String, @StringRes action: Int? = null, onAction: () -> Unit = {}) {
        val bar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.bottomNav)
        if (action != null) bar.setAction(action) { onAction() }
        bar.show()
    }

    private companion object {
        const val COUNTS_RELOAD_DELAY_MS = 3_000L
        const val KEY_TAB = "tab"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
