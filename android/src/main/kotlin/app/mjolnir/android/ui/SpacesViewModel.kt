package app.mjolnir.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.Messages
import app.mjolnir.client.Nicknames
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.claimAndAwait
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.SpaceRef
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An invite code for [spaceId]; [expiresAtMs] is on this phone's clock (counted from when the code arrived). */
data class InviteUi(val spaceId: String, val code: String, val expiresAtMs: Long)

/** A device that entered the invite code and waits for this phone, the admin, to accept it into the space. */
data class JoinRequest(val pairingId: String, val from: Peer, val spaceId: String, val spaceName: String)

/** The join flow as the dialog shows it. */
sealed interface JoinUi {
    data object Idle : JoinUi
    /** The code was claimed; the admin has to accept. */
    data class Waiting(val adminName: String) : JoinUi
    data class Joined(val spaceName: String) : JoinUi
    data class Failed(val message: String) : JoinUi
}

/** What a confirmed dialog does. */
sealed interface SpaceAction {
    data class Delete(val spaceId: String) : SpaceAction
    data class Leave(val spaceId: String) : SpaceAction
    data class Remove(val spaceId: String, val deviceId: String) : SpaceAction
    data class MakeAdmin(val spaceId: String, val deviceId: String) : SpaceAction
    data class TakeOver(val spaceId: String) : SpaceAction
}

/** A destructive or far-reaching action waits for a yes: [title] asks, [confirmLabel] is the button that does it. */
data class SpaceConfirm(val title: String, val confirmLabel: String, val action: SpaceAction)

data class SpacesUi(
    val spaces: List<Space> = emptyList(),
    val invite: InviteUi? = null,
    val request: JoinRequest? = null,
    val join: JoinUi = JoinUi.Idle,
    val confirm: SpaceConfirm? = null,
    /** The last failure of an action the user started, as a sentence; null once dismissed. */
    val notice: String? = null,
    /** Private nicknames by device id. */
    val nicknames: Map<String, String> = emptyMap(),
    /** This phone's device id (to tell its own admin rights and its own row); null until the first refresh. */
    val selfId: String? = null,
) {
    /** The name to show for [peer]: its nickname, else its real name. */
    fun nameOf(peer: Peer): String = nicknames[peer.deviceId] ?: peer.name
}

private const val NO_CONNECTION = "Can't reach the relay. Check your connection."
private const val LOST_CONNECTION = "Lost connection to the relay. Get a new code to keep waiting."
private const val GENERIC = "Something went wrong. Try again."
private const val KEY_SPACE = "inviteSpaceId"
private const val KEY_CODE = "inviteCode"
private const val KEY_EXPIRES = "inviteExpiresAt"

/**
 * The spaces this phone is in: the list, the admin's invite code, the join flow and the member and admin actions
 * (the phone twin of the desktop's SpacesController). There is no always-on event collector on the phone: spaces
 * and peers are refreshed when a screen is entered and after every action, and relay events are collected only while
 * an invite code shows, to receive the join request. The invite survives a rotation in [savedState].
 */
class SpacesViewModel(
    private val api: RelayApi,
    private val nicknames: Nicknames,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after a change that alters who this phone can throw to. */
    private val onPeersAffected: () -> Unit = {},
    /** Called when the relay rejects this phone's token, so the registration is reset (must not throw). */
    private val onUnauthorized: suspend () -> Unit = {},
    private val pollMs: Long = 2_000,
) : ViewModel() {
    private val _state = MutableStateFlow(SpacesUi(nicknames = nicknames.changes.value))
    val state: StateFlow<SpacesUi> = _state.asStateFlow()

    private var joinJob: Job? = null
    private var listenJob: Job? = null
    private var answerJob: Job? = null

    /** Spaces this phone deleted itself: their deleted event needs no "was deleted" notice. */
    private val deletedHere = HashSet<String>()

    init {
        viewModelScope.launch { nicknames.changes.collect { shown -> _state.update { it.copy(nicknames = shown) } } }
        restoreInvite()
    }

    /** Background refresh: a failure keeps the last list and stays silent (the next screen entry or action retries). */
    fun refresh() { viewModelScope.launch { quietly { fetch() } } }

    fun create(name: String) = act { api.createSpace(name) }
    fun rename(spaceId: String, name: String) = act { api.renameSpace(spaceId, name) }
    fun remove(spaceId: String, deviceId: String) = act { api.removeMember(spaceId, deviceId) }
    fun leave(spaceId: String) = act { api.leaveSpace(spaceId) }
    fun makeAdmin(spaceId: String, deviceId: String) = act { api.transferAdmin(spaceId, deviceId) }
    fun takeOver(spaceId: String) = act { api.takeOverAdmin(spaceId) }
    fun delete(spaceId: String) = act {
        deletedHere += spaceId
        try { api.deleteSpace(spaceId) } catch (e: Exception) { deletedHere -= spaceId; throw e }
    }

    /** Shows an invite code for [spaceId] and listens for the device that enters it. */
    fun invite(spaceId: String) { viewModelScope.launch { guarded { fetchInvite(spaceId) } } }

    fun dismissInvite() = setInvite(null)

    /** Accepts or rejects the pending join request, once: further taps while an answer is in flight do nothing. */
    fun answerRequest(accept: Boolean) {
        val request = _state.value.request ?: return
        if (answerJob?.isActive == true) return
        answerJob = viewModelScope.launch {
            var worked = true
            try {
                if (accept) api.acceptPairing(request.pairingId) else api.rejectPairing(request.pairingId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                worked = false
                // A reject that didn't reach the relay is not worth a message: the claim used the code up either way.
                if (accept || e.kind == RelayErrorKind.UNAUTHORIZED) {
                    notify(if (e.kind == RelayErrorKind.NOT_FOUND) "That request is gone." else failure(e))
                }
                if (e.kind == RelayErrorKind.UNAUTHORIZED) resetRegistration()
            }
            _state.update { it.copy(request = null) }
            onJoinRequestHandled(request.spaceId, accepted = accept && worked)
            if (accept && worked) afterChange()
        }
    }

    /**
     * The request to join [spaceId] was answered. The code is used up either way: after a reject or a failed accept a
     * fresh one is shown; after a successful accept the invite closes.
     */
    private suspend fun onJoinRequestHandled(spaceId: String, accepted: Boolean) {
        if (_state.value.invite?.spaceId == spaceId) {
            setInvite(null) // used up; a failed fetch must not leave the dead code showing
            if (!accepted) guarded { fetchInvite(spaceId) }
        }
        quietly { fetch() }
    }

    /** Claims [code] as a space invite and waits for the admin; an active attempt is not restarted. */
    fun join(code: String) {
        if (joinJob?.isActive == true) return
        _state.update { it.copy(join = JoinUi.Idle) }
        joinJob = viewModelScope.launch {
            var claimed: SpaceRef? = null
            // The claim answer names the space; claimAndAwait only hands back the admin.
            val recording = object : RelayApi by api {
                override suspend fun claimPairingCode(code: String, expect: CodeKind): ClaimResponse =
                    api.claimPairingCode(code, expect).also { claimed = it.space }
            }
            val outcome = claimAndAwait(
                recording, code, onWaiting = { admin -> _state.update { it.copy(join = JoinUi.Waiting(admin.name)) } },
                pollMs = pollMs, expect = CodeKind.SPACE_INVITE,
            )
            when (outcome) {
                is ClaimOutcome.Paired -> {
                    quietly { fetch() }
                    val name = claimed?.name ?: _state.value.spaces.find { it.adminId == outcome.peer.deviceId }?.name ?: "the space"
                    _state.update { it.copy(join = JoinUi.Joined(name)) }
                    afterChange()
                }
                is ClaimOutcome.Failed -> {
                    _state.update { it.copy(join = JoinUi.Failed(outcome.message)) }
                    if (outcome.kind == RelayErrorKind.UNAUTHORIZED) resetRegistration()
                }
            }
        }
    }

    fun dismissJoin() { if (joinJob?.isActive != true) _state.update { it.copy(join = JoinUi.Idle) } }

    fun askDelete(space: Space) = ask("Delete ${space.name}?", "Delete space", SpaceAction.Delete(space.id))
    fun askLeave(space: Space) = ask("Leave ${space.name}?", "Leave space", SpaceAction.Leave(space.id))
    fun askTakeOver(space: Space) = ask("Take over ${space.name}?", "Take over as admin", SpaceAction.TakeOver(space.id))
    fun askRemove(space: Space, member: Peer) =
        ask("Remove ${_state.value.nameOf(member)} from ${space.name}?", "Remove", SpaceAction.Remove(space.id, member.deviceId))
    fun askMakeAdmin(space: Space, member: Peer) =
        ask("Make ${_state.value.nameOf(member)} admin of ${space.name}?", "Make admin", SpaceAction.MakeAdmin(space.id, member.deviceId))

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** Runs the confirmed action and closes the dialog. */
    fun confirm() {
        val action = _state.value.confirm?.action ?: return
        dismissConfirm()
        when (action) {
            is SpaceAction.Delete -> delete(action.spaceId)
            is SpaceAction.Leave -> leave(action.spaceId)
            is SpaceAction.Remove -> remove(action.spaceId, action.deviceId)
            is SpaceAction.MakeAdmin -> makeAdmin(action.spaceId, action.deviceId)
            is SpaceAction.TakeOver -> takeOver(action.spaceId)
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * The relay says [space] is gone (this phone deleted it, or its admin did). Tells the user unless this phone
     * deleted it; returns whether it did.
     */
    fun onSpaceDeleted(space: SpaceRef): Boolean {
        if (_state.value.invite?.spaceId == space.id) setInvite(null)
        _state.update { it.copy(spaces = it.spaces.filter { s -> s.id != space.id }) }
        val tell = !deletedHere.remove(space.id)
        if (tell) notify("${space.name} was deleted.")
        refresh()
        viewModelScope.launch { afterChange() }
        return tell
    }

    private fun ask(title: String, label: String, action: SpaceAction) = _state.update { it.copy(confirm = SpaceConfirm(title, label, action)) }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch {
            if (guarded(action)) {
                quietly { fetch() }
                afterChange()
            }
        }
    }

    /** After a change to who this phone can reach: forget stale nicknames, then tell the throw screen. */
    private suspend fun afterChange() {
        quietly { pruneNicknames() }
        onPeersAffected()
    }

    /**
     * Refreshes the peers, then forgets nicknames of devices nothing links this phone to any more: no pairing and no
     * shared space (the relay lists a device that is both paired and in a space once). A failed refresh forgets nothing.
     */
    private suspend fun pruneNicknames() {
        val linked = api.peers().mapTo(HashSet()) { it.deviceId }
        nicknames.changes.value.keys.filter { it !in linked }.forEach(nicknames::remove)
    }

    private suspend fun fetch() {
        val list = api.spaces()
        _state.update { it.copy(spaces = list) }
        if (_state.value.invite?.let { inv -> list.none { it.id == inv.spaceId } } == true) setInvite(null)
        if (_state.value.selfId == null) {
            val me = api.me().deviceId
            _state.update { it.copy(selfId = me) }
        }
    }

    private suspend fun fetchInvite(spaceId: String) {
        val r = api.createInvite(spaceId)
        // Counted down locally from the moment the code arrived, so a skewed phone clock cannot shorten it.
        setInvite(InviteUi(spaceId, r.code, clock() + Limits.PAIRING_TTL_MS))
    }

    /** Shows [invite] (null: closes it), keeps it for a rotation, and listens for its join request only while it shows. */
    private fun setInvite(invite: InviteUi?) {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(invite = invite, request = if (invite == null) null else it.request?.takeIf { r -> r.spaceId == invite.spaceId }) }
        if (invite == null) {
            savedState.remove<String>(KEY_SPACE); savedState.remove<String>(KEY_CODE); savedState.remove<Long>(KEY_EXPIRES)
            return
        }
        savedState[KEY_SPACE] = invite.spaceId; savedState[KEY_CODE] = invite.code; savedState[KEY_EXPIRES] = invite.expiresAtMs
        val remaining = invite.expiresAtMs - clock()
        if (remaining > 0) listenJob = viewModelScope.launch { listen(invite.spaceId, remaining) }
    }

    private fun restoreInvite() {
        val spaceId = savedState.get<String>(KEY_SPACE) ?: return
        val code = savedState.get<String>(KEY_CODE) ?: return
        val expires = savedState.get<Long>(KEY_EXPIRES) ?: return
        if (expires <= clock()) return
        setInvite(InviteUi(spaceId, code, expires))
        refresh() // the space may be gone; a refresh that no longer lists it closes the invite
    }

    /** Collects relay events for [spaceId]'s join request, until [remainingMs] (the code's life) has run out. */
    private suspend fun listen(spaceId: String, remainingMs: Long) {
        try {
            withTimeoutOrNull(remainingMs) {
                api.events().collect { event ->
                    when {
                        event is RelayEvent.PairRequested && event.space?.id == spaceId ->
                            _state.update { s -> if (s.request == null) s.copy(request = JoinRequest(event.pairingId, event.from, spaceId, event.space!!.name)) else s }
                        event is RelayEvent.SpaceDeleted -> onSpaceDeleted(event.space)
                        event is RelayEvent.SpaceChanged -> refresh()
                        else -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) resetRegistration()
            else notify(LOST_CONNECTION) // no more events: the join request would never show, so say so
        }
    }

    /** Runs [block] for a refresh nobody asked for: no notice on failure, only a rejected token is passed on. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) resetRegistration()
        } catch (e: IOException) {
            // The nickname file couldn't be written; the nickname stays until the next change.
        }
    }

    /** Runs [block] for an action the user started; a failure becomes the notice. Returns whether it worked. */
    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            notify(failure(e))
            if (e.kind == RelayErrorKind.UNAUTHORIZED) resetRegistration()
            return false
        }
    }

    private fun notify(text: String) = _state.update { it.copy(notice = text) }

    private fun failure(e: RelayException) = when (e.kind) {
        RelayErrorKind.NETWORK -> NO_CONNECTION
        RelayErrorKind.UNAUTHORIZED -> Messages.RESET
        else -> GENERIC
    }

    private suspend fun resetRegistration() {
        _state.update { it.copy(spaces = emptyList(), request = null, selfId = null) }
        setInvite(null)
        try {
            onUnauthorized()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The user sees the reset message either way; registration is retried on the next use.
        }
    }
}
