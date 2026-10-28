package moe.tenshii.blockextremedroite.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.transition.TransitionManager
import android.view.View
import androidx.annotation.RequiresApi
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
import moe.tenshii.blockextremedroite.AccountSource
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
import moe.tenshii.blockextremedroite.SettingsStore
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
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var accounts: List<Account> = emptyList()
    private var listLoadFailed = false
    private var refreshing = false
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
            buildFilterChips()
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
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        repository = AccountRepository(this)
        progress = ProgressStore(this)
        settings = SettingsStore(this)
        counter = BlockCounter.get(this)
        updater = Updater(this)

        binding.viewAccounts.setOnClickListener { startActivity(Intent(this, AccountsActivity::class.java)) }
        binding.enableService.setOnClickListener { showDisclosure() }
        binding.installApp.setOnClickListener { openStorePage(settings.platform) }
        binding.openRepository.setOnClickListener { openRepository() }
        binding.refreshList.setOnClickListener { refreshList() }
        binding.startStop.setOnClickListener { toggleRun() }
        binding.resetProgress.setOnClickListener { confirmReset() }
        binding.updateInstall.setOnClickListener { startUpdate() }
        binding.updateNotes.setOnClickListener { showUpdateNotes() }
        binding.checkUpdate.setOnClickListener { checkForUpdate(manual = true) }
        binding.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        bindSection(binding.listHeader, binding.listChevron, binding.listBody, settings::listExpanded)
        bindSection(binding.pacingHeader, binding.pacingChevron, binding.pacingBody, settings::pacingExpanded)
        setUpPacing()
        loadList()
        loadCounts()
        counter.flush()
        update = updater.available
        checkForUpdate(manual = false)
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

    private fun loadList() {
        refreshing = true
        executor.execute {
            val result = runCatching { repository.load() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                result.onSuccess { accounts = it }
                listLoadFailed = result.isFailure
                buildFilterChips()
                render()
            }
        }
    }

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

    private fun refreshList() {
        if (refreshing) return
        refreshing = true
        render()
        executor.execute {
            val result = runCatching { repository.refresh() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                result.onSuccess {
                    accounts = it
                    listLoadFailed = false
                    buildFilterChips()
                    snackbar(resources.getQuantityString(R.plurals.list_refresh_done, it.size, it.size))
                }.onFailure {
                    snackbar(getString(R.string.list_refresh_failed))
                }
                render()
            }
        }
    }

    /**
     * Each platform counts what is left to block there in the current selection. Source and category counts
     * follow the selected platform, and category counts the selected sources, so the chips are built in that order.
     */
    private fun buildFilterChips() {
        val platform = settings.platform
        binding.platforms.removeAllViews()
        for (option in Platform.entries) {
            val pending = settings.select(accounts, option).count { !progress.status(it.profiles.getValue(option)).isDone }
            val chip = ItemCategoryChipBinding.inflate(layoutInflater, binding.platforms, false).root.apply {
                id = View.generateViewId()
                text = getString(R.string.category_chip, option.label, pending)
                isChecked = option == platform
                setOnCheckedChangeListener { _, checked ->
                    if (!checked || settings.platform == option) return@setOnCheckedChangeListener
                    settings.platform = option
                    appInstalled = BlockService.isInstalled(this@MainActivity, option)
                    RunMonitor.update { it.copy(lastStop = null) }
                    buildFilterChips()
                    render()
                }
            }
            binding.platforms.addView(chip)
        }

        val onPlatform = accounts.filter { platform in it.profiles }
        val excludedSources = settings.excludedSources
        val sourceCounts = onPlatform.groupingBy { it.source }.eachCount()
        binding.sources.removeAllViews()
        for (source in AccountSource.entries) {
            val count = sourceCounts[source] ?: continue
            val label = getString(if (source == AccountSource.CURATED) R.string.source_curated else R.string.source_community)
            val chip = ItemCategoryChipBinding.inflate(layoutInflater, binding.sources, false).root.apply {
                text = getString(R.string.category_chip, label, count)
                isChecked = source !in excludedSources
                setOnCheckedChangeListener { _, checked ->
                    settings.excludedSources =
                        if (checked) settings.excludedSources - source else settings.excludedSources + source
                    buildFilterChips()
                    render()
                }
            }
            binding.sources.addView(chip)
        }

        val excluded = settings.excludedCategories
        val counts = onPlatform.filter { it.source !in excludedSources }
            .groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
        binding.categories.removeAllViews()
        for ((category, count) in counts) {
            val chip = ItemCategoryChipBinding.inflate(layoutInflater, binding.categories, false).root.apply {
                text = getString(R.string.category_chip, category, count)
                isChecked = category !in excluded
                setOnCheckedChangeListener { _, checked ->
                    settings.excludedCategories =
                        if (checked) settings.excludedCategories - category else settings.excludedCategories + category
                    buildFilterChips()
                    render()
                }
            }
            binding.categories.addView(chip)
        }
    }

    private fun selectedAccounts(): List<Account> = settings.select(accounts)

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

        binding.listInfo.text = when {
            listLoadFailed -> getString(R.string.list_load_failed)
            accounts.isEmpty() -> getString(R.string.list_loading)
            else -> resources.getQuantityString(
                R.plurals.list_updated, accounts.size, accounts.size,
                DateUtils.formatDateTime(this, repository.lastUpdated, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
            )
        }
        binding.refreshList.isEnabled = !refreshing && !state.running
        binding.refreshList.setText(if (refreshing) R.string.list_refreshing else R.string.list_refresh)
        for (group in listOf(binding.platforms, binding.sources, binding.categories)) {
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !state.running
        }
        binding.delaySlider.isEnabled = !state.running
        binding.capSlider.isEnabled = !state.running
        binding.resetProgress.isEnabled = !state.running

        val statuses = selectedAccounts().map { progress.status(it.profiles.getValue(platform)) }
        binding.viewAccounts.isEnabled = accounts.isNotEmpty()
        val pending = statuses.count { !it.isDone }
        val blocked = statuses.count { it == BlockStatus.BLOCKED || it == BlockStatus.ALREADY_BLOCKED }
        val unavailable = statuses.count { it == BlockStatus.UNAVAILABLE }
        binding.summary.text = getString(R.string.summary, pending, blocked, unavailable)

        binding.startStop.setText(if (state.running) R.string.stop else R.string.start)
        binding.startStop.isEnabled = state.running || (appInstalled && state.serviceConnected && pending > 0)

        val runText = runStatusText(state, statuses.isEmpty(), pending)
        binding.runStatus.text = runText
        binding.runStatus.visibility = if (runText == null) View.GONE else View.VISIBLE
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

    private fun runStatusText(state: RunState, noSelection: Boolean, pending: Int): String? = when {
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
        (settings.excludedSources.isNotEmpty() || settings.excludedCategories.isNotEmpty()) && noSelection ->
            getString(R.string.no_category)
        pending == 0 -> getString(R.string.nothing_to_block)
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
        val platform = settings.platform
        val queue = selectedAccounts().filter { !progress.status(it.profiles.getValue(platform)).isDone }
        if (queue.isEmpty()) {
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
                    if (manual && it == null) snackbar(getString(R.string.update_none))
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

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val COUNTS_RELOAD_DELAY_MS = 3_000L
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
