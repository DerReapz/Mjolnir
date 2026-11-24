package app.mjolnir.desktop

import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.claimAndAwait
import app.mjolnir.client.spaceErrorCopy
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.SpaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An invite code for [spaceId]; [expiresAtMs] is on the controller's clock (counted from when the code arrived). */
data class InviteUi(val spaceId: String, val code: String, val expiresAtMs: Long)

/** The join flow as the dialog shows it. */
sealed interface JoinUi {
    data object Idle : JoinUi
    /** The code was claimed; the admin has to accept. */
    data class Waiting(val adminName: String) : JoinUi
    data class Joined(val spaceName: String) : JoinUi
    data class Failed(val message: String) : JoinUi
}

private const val NO_CONNECTION = "Can't reach the relay. Check your connection."
private const val GENERIC = "Something went wrong. Try again."

/**
 * The Spaces section's logic: the list of spaces this device is in, the admin's invite code, the join flow, and the
 * member and admin actions. Every action runs in [scope]; a failure appears in [notice].
 * [onPeersAffected] runs after a change that alters who this device can send to; [onUnauthorized] when the relay
 * rejected this device's token.
 */
class SpacesController(
    private val api: () -> RelayApi?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPeersAffected: () -> Unit = {},
    private val onUnauthorized: () -> Unit = {},
    /** Runs with every space this device is in each time the list is (re)loaded. */
    private val onSpacesLoaded: (List<Space>) -> Unit = {},
) {
    private val _spaces = MutableStateFlow<List<Space>>(emptyList())
    val spaces: StateFlow<List<Space>> = _spaces.asStateFlow()

    private val _invite = MutableStateFlow<InviteUi?>(null)
    val invite: StateFlow<InviteUi?> = _invite.asStateFlow()

    private val _join = MutableStateFlow<JoinUi>(JoinUi.Idle)
    val join: StateFlow<JoinUi> = _join.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** The last failure of an action, as a sentence; null once dismissed. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var joinJob: Job? = null

    /** Spaces this device deleted itself: their [onSpaceDeleted] event needs no "was deleted" notice. */
    private val deletedHere = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Background refresh: a failure keeps the last list and stays silent (the next connect or event retries). */
    fun refresh(): Job = scope.launch { quietly { fetch() } }

    fun create(name: String): Job = scope.launch { if (guarded { requireApi().createSpace(name) }) { quietly { fetch() }; onPeersAffected() } }

    fun invite(spaceId: String): Job = scope.launch { guarded { fetchInvite(spaceId) } }

    fun dismissInvite() { _invite.value = null }

    /**
     * The request to join [spaceId] was answered. The code is used up either way: after a reject or a failed accept a
     * fresh one is shown; after a successful accept the invite closes.
     */
    fun onJoinRequestHandled(spaceId: String, accepted: Boolean): Job = scope.launch {
        if (_invite.value?.spaceId == spaceId) {
            _invite.value = null // used up; a failed fetch must not leave the dead code showing
            if (!accepted) guarded { fetchInvite(spaceId) }
        }
        quietly { fetch() }
    }

    /** Claims [code] as a space invite and waits for the admin; an active attempt is not restarted. */
    fun join(code: String): Job? {
        if (joinJob?.isActive == true) return null
        _join.value = JoinUi.Idle
        return scope.launch {
            val api = api()
            if (api == null) { _join.value = JoinUi.Failed(NO_CONNECTION); return@launch }
            var claimed: SpaceRef? = null
            // The claim answer names the space; claimAndAwait only hands back the admin.
            val recording = object : RelayApi by api {
                override suspend fun claimPairingCode(code: String, expect: CodeKind): ClaimResponse =
                    api.claimPairingCode(code, expect).also { claimed = it.space }
            }
            val outcome = claimAndAwait(recording, code, onWaiting = { _join.value = JoinUi.Waiting(it.name) }, expect = CodeKind.SPACE_INVITE)
            when (outcome) {
                is ClaimOutcome.Paired -> {
                    quietly { fetch() }; onPeersAffected()
                    val name = claimed?.name ?: _spaces.value.find { it.adminId == outcome.peer.deviceId }?.name ?: "the space"
                    _join.value = JoinUi.Joined(name)
                }
                is ClaimOutcome.Failed -> {
                    _join.value = JoinUi.Failed(outcome.message)
                    if (outcome.kind == RelayErrorKind.UNAUTHORIZED) onUnauthorized()
                }
            }
        }.also { joinJob = it }
    }

    fun dismissJoin() { if (joinJob?.isActive != true) _join.value = JoinUi.Idle }

    fun remove(spaceId: String, deviceId: String): Job = change { it.removeMember(spaceId, deviceId) }
    fun leave(spaceId: String): Job = change { it.leaveSpace(spaceId) }
    fun rename(spaceId: String, name: String): Job = change { it.renameSpace(spaceId, name) }
    fun makeAdmin(spaceId: String, deviceId: String): Job = change { it.transferAdmin(spaceId, deviceId) }
    fun takeOver(spaceId: String): Job = change { it.takeOverAdmin(spaceId) }
    fun delete(spaceId: String): Job = change {
        deletedHere += spaceId
        try { it.deleteSpace(spaceId) } catch (e: Exception) { deletedHere -= spaceId; throw e }
    }

    /** The relay says [space] is gone (this device deleted it, or its admin did). Returns whether to tell the user. */
    fun onSpaceDeleted(space: SpaceRef): Boolean {
        if (_invite.value?.spaceId == space.id) _invite.value = null
        _spaces.value = _spaces.value.filter { it.id != space.id }
        refresh()
        return !deletedHere.remove(space.id)
    }

    /** Forgets everything (the registration was reset or the relay changed). */
    fun clear() {
        _spaces.value = emptyList(); _invite.value = null; _join.value = JoinUi.Idle; _notice.value = null
    }

    fun dismissNotice() { _notice.value = null }

    private fun change(action: suspend (RelayApi) -> Unit): Job = scope.launch {
        if (guarded { action(requireApi()) }) { quietly { fetch() }; onPeersAffected() }
    }

    private fun requireApi(): RelayApi = api() ?: throw RelayException(RelayErrorKind.NETWORK, "no relay configured")

    private suspend fun fetch() {
        val list = requireApi().spaces()
        _spaces.value = list
        onSpacesLoaded(list)
        if (_invite.value?.let { inv -> list.none { it.id == inv.spaceId } } == true) _invite.value = null
    }

    private suspend fun fetchInvite(spaceId: String) {
        val r = requireApi().createInvite(spaceId)
        // Counted down locally from the moment the code arrived, so a skewed PC clock cannot shorten it.
        _invite.value = InviteUi(spaceId, r.code, clock() + Limits.PAIRING_TTL_MS)
    }

    /** Runs [block] for a refresh nobody asked for: no notice on failure, only a rejected token is passed on. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) onUnauthorized()
        }
    }

    /** Runs [block] for an action the user started; a failure becomes the [notice]. Returns whether it worked. */
    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            _notice.value = spaceErrorCopy(e) ?: if (e.kind == RelayErrorKind.NETWORK) NO_CONNECTION else GENERIC
            if (e.kind == RelayErrorKind.UNAUTHORIZED) onUnauthorized()
            return false
        }
    }
}
