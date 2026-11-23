package app.mjolnir.android.update

import android.content.pm.PackageInstaller
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayException
import app.mjolnir.client.UpdateState
import app.mjolnir.client.Updater
import app.mjolnir.protocol.RelayEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The `Failed` reason when the APK couldn't be handed to the system installer (the session couldn't be written). */
const val INSTALLER_DIDNT_START = "the installer didn't start"

/** The `Failed` reason for an APK signed with another key; shown as [Messages.UPDATE_SIGNED_DIFFERENTLY]. */
internal const val SIGNED_DIFFERENTLY = "the update is signed differently"

/** What the update strip above the screens shows: its text and whether it offers *Install*. */
data class UpdateBannerUi(val text: String, val install: Boolean)

/**
 * The update strip for [state]. [relayTooOld] takes the slot until the user has tapped Install (progress and errors of
 * an install they started win); [awaitingPermission] is a `Ready` file waiting for the user to allow installs.
 */
fun updateBanner(state: UpdateState, relayTooOld: Boolean, awaitingPermission: Boolean): UpdateBannerUi? = when (state) {
    is UpdateState.Downloading -> UpdateBannerUi(Messages.downloadingUpdate(state.percent), install = false)
    // The same APK would be refused again, so there is nothing to retry.
    is UpdateState.Failed -> UpdateBannerUi(failedText(state.reason), install = state.reason != SIGNED_DIFFERENTLY)
    is UpdateState.Ready ->
        if (awaitingPermission) UpdateBannerUi(Messages.ALLOW_INSTALLS, install = true)
        else UpdateBannerUi(Messages.INSTALLING, install = false)
    else -> when {
        relayTooOld -> UpdateBannerUi(Messages.RELAY_TOO_OLD, install = false)
        state is UpdateState.Available -> UpdateBannerUi(Messages.updateAvailable(state.version), install = true)
        else -> null
    }
}

private fun failedText(reason: String) =
    if (reason == SIGNED_DIFFERENTLY) Messages.UPDATE_SIGNED_DIFFERENTLY else Messages.updateFailed(reason)

/**
 * The banner text for a [PackageInstaller] session status: null for success or a pending user confirmation (not an
 * error), the reinstall hint for an APK signed with another key, else `Update failed: … Try again.`
 */
fun installStatusMessage(status: Int, message: String?): String? = installFailureReason(status, message)?.let(::failedText)

/** The `Failed` reason for [status], or null when it isn't a failure. [message] only helps spot a signature clash. */
internal fun installFailureReason(status: Int, message: String?): String? = when (status) {
    PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> SIGNED_DIFFERENTLY
    PackageInstaller.STATUS_FAILURE_ABORTED -> "the install was cancelled"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "the phone is out of space"
    // Some versions report a certificate clash as a plain or invalid-APK failure; its detail still names it.
    else -> if (message != null && SIGNATURE_CLASH.any { message.contains(it, ignoreCase = true) }) SIGNED_DIFFERENTLY
        else "the installer didn't finish"
}

private val SIGNATURE_CLASH = listOf("INCONSISTENT_CERTIFICATES", "UPDATE_INCOMPATIBLE", "signatures do not match")

/**
 * The phone's side of an update: *Install* downloads (or retries) through the [Updater], then hands the verified APK to
 * the [ApkInstaller]; the session's outcome comes back through [onInstallStatus]. Never installs on its own.
 */
class UpdateFlow(private val updater: Updater, private val installer: ApkInstaller, private val scope: CoroutineScope) {
    private val awaitingPermission = MutableStateFlow(false)
    private val busy = AtomicBoolean(false)

    /** The update strip, or null when there is nothing to show. */
    val banner: StateFlow<UpdateBannerUi?> = combine(updater.state, updater.relayTooOld, awaitingPermission, ::updateBanner)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The user tapped Install. A second tap while this runs does nothing, so the APK is never handed over twice. */
    fun install(): Job = scope.launch {
        if (!busy.compareAndSet(false, true)) return@launch
        try {
            awaitingPermission.value = false
            val s = updater.state.value
            if (s is UpdateState.Available || s is UpdateState.Failed) updater.download()
            val ready = updater.state.value as? UpdateState.Ready ?: return@launch
            val handedOver = try {
                installer.install(ready.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updater.installFailed(INSTALLER_DIDNT_START)
                return@launch
            }
            if (!handedOver) awaitingPermission.value = true
        } finally {
            busy.set(false)
        }
    }

    /** A status from the install session ([PackageInstaller.EXTRA_STATUS]); a failure shows in the banner. */
    fun onInstallStatus(status: Int, message: String?) {
        installFailureReason(status, message)?.let(updater::installFailed)
    }
}

/**
 * Listens to the relay while [active] (the app in the foreground) so the relay's `UpdateAvailable` push, sent on every
 * connect to an app older than the relay, reaches [onUpdateAvailable]. A dropped connection is retried.
 */
class UpdateWatcher(
    private val events: () -> Flow<RelayEvent>,
    scope: CoroutineScope,
    active: Flow<Boolean>,
    private val onUpdateAvailable: (String) -> Unit,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    init {
        scope.launch {
            active.distinctUntilChanged().collectLatest { on -> if (on) listen() }
        }
    }

    private suspend fun listen() {
        while (true) {
            try {
                events().collect { e -> if (e is RelayEvent.UpdateAvailable) onUpdateAvailable(e.version) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RelayException) {
                // Not registered yet, or the relay is unreachable: tried again below.
            }
            delay(retryDelayMs)
        }
    }

    private companion object { const val RETRY_DELAY_MS = 30_000L }
}
