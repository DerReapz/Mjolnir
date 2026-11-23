package app.mjolnir.android.update

import android.content.Intent
import android.content.pm.PackageInstaller
import app.mjolnir.client.Messages
import app.mjolnir.client.UpdateState
import app.mjolnir.client.Updater
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Versions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
 * an install they started win); [awaitingPermission] is a `Ready` file waiting for the user to allow installs, and
 * [tapToInstall] one that needs a tap (not handed over yet, or its session waits on a prompt the app couldn't open).
 */
fun updateBanner(state: UpdateState, relayTooOld: Boolean, awaitingPermission: Boolean, tapToInstall: Boolean = false): UpdateBannerUi? = when (state) {
    is UpdateState.Downloading -> UpdateBannerUi(Messages.downloadingUpdate(state.percent), install = false)
    // The same APK would be refused again, so there is nothing to retry.
    is UpdateState.Failed -> UpdateBannerUi(failedText(state.reason), install = state.reason != SIGNED_DIFFERENTLY)
    is UpdateState.Ready ->
        when {
            awaitingPermission -> UpdateBannerUi(Messages.ALLOW_INSTALLS, install = true)
            tapToInstall -> UpdateBannerUi(Messages.updateAvailable(state.version), install = true)
            else -> UpdateBannerUi(Messages.INSTALLING, install = false)
        }
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

/** A committed install session; [confirm] is the system's prompt once it asked for the user's confirmation. */
private data class LiveSession(val id: Int, val confirm: Intent? = null)

/**
 * The phone's side of an update: *Install* downloads (or retries) through the [Updater], then hands the verified APK to
 * the [ApkInstaller]; the session's outcome comes back through [onConfirmationNeeded] and [onInstallStatus], which
 * ignore any session but the current one. Never installs on its own.
 *
 * The system's prompt is only started while [foreground] (a start from the background is blocked silently); until the
 * session ends, the banner offers Install again, which opens the prompt the session is waiting on.
 */
class UpdateFlow(
    private val updater: Updater,
    private val installer: ApkInstaller,
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
) {
    private val awaitingPermission = MutableStateFlow(false)
    private val handingOver = MutableStateFlow(false)
    private val session = MutableStateFlow<LiveSession?>(null)

    /** The update strip, or null when there is nothing to show. */
    val banner: StateFlow<UpdateBannerUi?> =
        combine(updater.state, updater.relayTooOld, awaitingPermission, handingOver, session) { state, tooOld, permission, busy, live ->
            updateBanner(state, tooOld, permission, tapToInstall = !busy && (live == null || live.confirm != null))
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The user tapped Install. A second tap while this runs does nothing; with a session already handed over, it opens
     * that session's prompt (if it is waiting on one) instead of committing another.
     */
    fun install(): Job = scope.launch {
        if (!handingOver.compareAndSet(expect = false, update = true)) return@launch
        try {
            awaitingPermission.value = false
            val s = updater.state.value
            if (s is UpdateState.Available || s is UpdateState.Failed) updater.download()
            val ready = updater.state.value as? UpdateState.Ready ?: return@launch
            session.value?.let { live ->
                live.confirm?.let { launchConfirm(live.id, it) }
                return@launch
            }
            val handedOver = try {
                installer.install(ready.file) { id -> session.value = LiveSession(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.value = null
                updater.installFailed(INSTALLER_DIDNT_START)
                return@launch
            }
            if (!handedOver) awaitingPermission.value = true
        } finally {
            handingOver.value = false
        }
    }

    /** Session [sessionId] waits for the user to confirm with [confirm]; opened now if the app is in the foreground. */
    fun onConfirmationNeeded(sessionId: Int, confirm: Intent?) {
        val live = session.value?.takeIf { it.id == sessionId } ?: return
        if (confirm == null) {
            installer.abandon(sessionId)
            return endSession(sessionId, PackageInstaller.STATUS_FAILURE, null)
        }
        session.value = live.copy(confirm = confirm)
        if (foreground.value) launchConfirm(sessionId, confirm)
    }

    /** A final status of session [sessionId] ([PackageInstaller.EXTRA_STATUS]); a failure shows in the banner. */
    fun onInstallStatus(sessionId: Int, status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        if (session.value?.id != sessionId) return // an earlier session's late answer
        endSession(sessionId, status, message)
    }

    private fun endSession(sessionId: Int, status: Int, message: String?) {
        val live = session.value ?: return
        if (live.id != sessionId || !session.compareAndSet(live, null)) return
        installFailureReason(status, message)?.let(updater::installFailed)
    }

    /** Opens the prompt; one that can't be opened at all ends (and abandons) the session. */
    private fun launchConfirm(sessionId: Int, confirm: Intent) {
        try {
            installer.launchConfirm(confirm)
        } catch (e: Exception) {
            installer.abandon(sessionId)
            endSession(sessionId, PackageInstaller.STATUS_FAILURE, null)
        }
    }

    /**
     * The app came to the foreground: a session the system dropped without a word (some prompts send nothing when
     * dismissed) is let go, so the banner offers Install again.
     */
    fun onForeground() {
        val live = session.value ?: return
        if (!installer.isLive(live.id)) session.compareAndSet(live, null)
    }
}

/**
 * The relay's version from a response header: tells [updater] whether the relay is older than this app, and a relay
 * newer than the app means a release is out (T2's updater skips repeats and throttles the GitHub check).
 */
fun forwardRelayVersion(updater: Updater, appVersion: String, relayVersion: String) {
    updater.onRelayVersion(relayVersion)
    if (Versions.isNewer(relayVersion, appVersion)) updater.onUpdateAvailable(relayVersion)
}

/** Passes the relay's `UpdateAvailable` push on to [updater] from an event stream the app already listens to. */
fun Flow<RelayEvent>.forwardingUpdates(updater: Updater): Flow<RelayEvent> =
    onEach { if (it is RelayEvent.UpdateAvailable) updater.onUpdateAvailable(it.version) }
