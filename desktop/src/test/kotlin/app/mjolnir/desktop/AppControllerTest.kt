@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class AppControllerTest {
    private val phone = Peer("ph", "Pixel 8", DeviceKind.PHONE)
    @TempDir lateinit var dir: File

    private fun TestScope.controller(
        api: RelayApi, urls: MutableList<String> = mutableListOf(),
        launchAtLogin: (Boolean) -> Boolean = { false }, sendTo: (Boolean) -> Boolean = { false },
        config: String = """{"relayUrl":"https://a.example","deviceId":"old-id","token":"old-token","deviceName":"PC","saveDir":"${File(dir, "save").path.replace("\\", "\\\\")}"}""",
    ): Pair<AppController, CoroutineScope> {
        File(dir, "config.json").writeText(config)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return AppController(dir, scope, launchAtLoginSetter = launchAtLogin, sendToSetter = sendTo) { url, _ -> urls += url; api } to scope
    }

    private fun TestScope.notes(c: AppController): MutableList<String> {
        val notes = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.notifications.collect { notes += it } }
        return notes
    }

    // the accept call outlives the dialog's scope; the request clears only after the relay answered
    @Test fun acceptCompletesAfterWindowScopeIsCancelled() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val gate = CompletableDeferred<Unit>()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) { gate.await(); fake.acceptPairing(pairingId) }
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)

        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { c.answerPairRequest(req, accept = true) }; runCurrent()
        assertEquals(req, c.pairRequest.value) // still showing while the call is in flight
        assertNull(c.answerPairRequest(req, accept = true)) // a second click is ignored
        window.cancel() // the dialog leaves the composition
        gate.complete(Unit); runCurrent()

        assertEquals(listOf("pair-1"), fake.accepted)
        assertNull(c.pairRequest.value)
        assertEquals(listOf(phone), c.peers.value)
        assertEquals(listOf("Paired with Pixel 8"), notes)
        scope.cancel()
    }

    // a failed answer still clears the request and says what went wrong in a sentence
    @Test fun failedRejectClearsRequestWithSentence() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun rejectPairing(pairingId: String) = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = false); runCurrent()
        assertNull(c.pairRequest.value)
        assertEquals(listOf("Couldn't answer the request: no connection."), notes)
        scope.cancel()
    }

    // accepting with no target yet makes the new device the target; an existing target is kept
    @Test fun acceptRemembersTheNewDeviceOnlyWhenNoTarget() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        assertNull(c.config.value.lastPhoneId)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals("ph", c.config.value.lastPhoneId)

        val other = Peer("ph2", "Pixel 6", DeviceKind.PHONE)
        val req2 = RelayEvent.PairRequested("pair-2", other)
        c.onPairRequested(req2)
        c.answerPairRequest(req2, accept = true); runCurrent()
        assertEquals("ph", c.config.value.lastPhoneId)
        scope.cancel()
    }

    // the claim used the code up, so a reject asks the pair window for a fresh one; an accept does not
    @Test fun rejectAsksForAFreshCode() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        var fresh = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.codeUsedUp.collect { fresh++ } }
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = false); runCurrent()
        assertEquals(listOf("pair-1"), fake.rejected)
        assertEquals(1, fresh)
        val req2 = RelayEvent.PairRequested("pair-2", phone)
        c.onPairRequested(req2)
        c.answerPairRequest(req2, accept = true); runCurrent()
        assertEquals(1, fresh)
        scope.cancel()
    }

    // a failed accept can't be retried (the dialog is gone) and the claim used the code up: ask for a fresh one
    @Test fun failedAcceptAsksForAFreshCode() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        var fresh = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.codeUsedUp.collect { fresh++ } }
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals(1, fresh)
        assertEquals(listOf("Couldn't answer the request: no connection."), notes)
        assertNull(c.config.value.lastPhoneId)
        scope.cancel()
    }

    // changing the relay runs in the controller's scope, clears credentials and registers on the new relay
    @Test fun changeRelayReRegistersOnNewRelay() = runTest {
        val fake = FakeRelayApi(); val urls = mutableListOf<String>()
        val (c, scope) = controller(fake, urls)
        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { c.changeRelay("https://b.example") }
        runCurrent(); window.cancel(); runCurrent()
        assertEquals("https://b.example", c.config.value.relayUrl)
        assertEquals("tok-1", c.config.value.token); assertEquals("dev-1", c.config.value.deviceId)
        assertEquals(listOf("PC" to DeviceKind.PC), fake.registerCalls)
        assertEquals("https://b.example", urls.last())
        scope.cancel()
    }

    // a hand-edited config with plain http to a remote host never gets a client
    @Test fun plainHttpToRemoteRelayGetsNoClient() = runTest {
        File(dir, "config.json").writeText(
            """{"relayUrl":"http://relay.example.com","deviceName":"PC","saveDir":"${File(dir, "save").path.replace("\\", "\\\\")}"}"""
        )
        val urls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = AppController(dir, scope) { url, _ -> urls += url; FakeRelayApi() }
        assertNull(c.api())
        c.start(); runCurrent()
        assertTrue(urls.isEmpty())
        scope.cancel()
    }

    // a file that cannot be read says so instead of blaming the relay
    @Test fun unreadableFileNotificationNamesTheFile() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        val gone = File(dir, "gone.txt")
        c.send(phone, emptyList(), listOf(LocalUpload(gone.absolutePath, "gone.txt", "text/plain", 4)))
        val deadline = System.currentTimeMillis() + 5_000 // the read itself runs on Dispatchers.IO
        while (notes.none { it.startsWith("Not sent") } && System.currentTimeMillis() < deadline) { runCurrent(); Thread.sleep(10) }
        assertTrue("Not sent: Couldn't read gone.txt." in notes, notes.toString())
        assertTrue(fake.sent.isEmpty())
        scope.cancel()
    }

    @Test fun pairingSentences() {
        assertEquals("Couldn't get a code: no connection.", couldntGetCode("no connection"))
        assertEquals("Couldn't answer the request: the relay had a problem.", couldntAnswer("the relay had a problem"))
    }

    @Test fun queueSurvivesWindowClose() = runTest {
        val (c, _) = controller(FakeRelayApi())
        c.updateSend { it.withText("https://a.com") }
        c.openWindow(); c.openWindow()
        assertEquals(listOf("https://a.com"), c.send.value.links)
    }

    @Test fun sendSelectedKeepsUnchecked() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com https://b.com").toggle("https://b.com") }
        c.sendSelected().join()
        assertEquals(listOf("https://b.com"), c.send.value.links)
        assertTrue(c.send.value.selected.isEmpty())
    }

    @Test fun handOffOpensSendTabOnce() = runTest {
        val (c, _) = controller(FakeRelayApi())
        val opened = mutableListOf<MainTab>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.openRequests.collect { opened += it } }
        val f1 = File(dir, "one.txt").apply { writeText("1") }
        val f2 = File(dir, "two.txt").apply { writeText("2") }
        c.handOff(listOf(f1)).join()
        c.handOff(listOf(f2)).join()
        assertEquals(listOf(MainTab.SEND, MainTab.SEND), opened)
        assertEquals(2, c.send.value.files.size)
    }

    @Test fun uncheckedFileStaysAfterSend() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.txt").apply { writeText("b") }
        c.updateSend { it.withTargets(listOf(phone), null).withFiles(listOf(a, b)) }
        val keyB = uploadUnitKey(c.send.value.files.single { it.relativePath == "b.txt" })
        c.updateSend { it.toggle(keyB) }
        c.sendSelected().join()
        assertEquals(listOf("b.txt"), c.send.value.files.map { it.relativePath })
    }

    @Test fun sendNoticeGoesToTrayOnlyWhileWindowHidden() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        val notes = notes(c)
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com") }
        c.mainWindowVisible = true
        c.sendSelected().join()
        assertTrue(notes.isEmpty()); assertNotNull(c.send.value.notice)
        c.mainWindowVisible = false
        c.updateSend { it.withText("https://b.com") }
        c.sendSelected().join()
        assertEquals(1, notes.size)
    }

    @Test fun sendingClearsSelectedRowOfSentItem() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com").selectRow("https://a.com") }
        c.sendSelected().join()
        assertNull(c.send.value.selectedRow)
    }
    // ---- M5 ----------------------------------------------------------------------------------------------------

    @Test fun saveAppliesAllFields() = runTest {
        val fake = FakeRelayApi()
        val launched = mutableListOf<Boolean>(); val sendTos = mutableListOf<Boolean>()
        val (c, scope) = controller(fake, launchAtLogin = { launched += it; true }, sendTo = { sendTos += it; true })
        val draft = SettingsDraft(
            relayUrl = "https://b.example/", deviceName = " Desk ", saveDir = File(dir, "elsewhere").path,
            openLinksOnArrival = false, launchAtLogin = true, sendToMenu = false, keepInTray = false,
        )
        var result: String? = "unset"
        c.saveSettings(draft) { result = it }.join(); runCurrent()
        assertNull(result)
        val cfg = c.config.value
        assertEquals("https://b.example", cfg.relayUrl)
        assertEquals("Desk", cfg.deviceName)
        assertEquals(File(dir, "elsewhere").path, cfg.saveDir)
        assertFalse(cfg.openLinksOnArrival); assertFalse(cfg.keepInTray)
        assertTrue(cfg.launchAtLogin); assertFalse(cfg.sendToMenu)
        assertEquals(listOf(true), launched); assertEquals(listOf(false), sendTos)
        assertEquals("tok-1", cfg.token) // the relay switch registered anew
        assertEquals("Desk", fake.registerCalls.last().first) // under the new name
        scope.cancel()
    }

    @Test fun saveLeavesUnchangedOsSettingsAlone() = runTest {
        val (c, scope) = controller(
            FakeRelayApi(), launchAtLogin = { fail("not touched") }, sendTo = { fail("not touched") },
            config = """{"relayUrl":"https://a.example","deviceId":"d","token":"t","deviceName":"PC"}""",
        )
        val draft = c.config.value.toDraft().copy(deviceName = "Desk")
        assertNull(c.applySettings(draft))
        assertEquals("Desk", c.config.value.deviceName)
        assertNull(c.config.value.saveDir) // the shown default is not stored as a custom folder
        scope.cancel()
    }

    @Test fun saveRejectsAnInvalidDraftWithoutChangingAnything() = runTest {
        val (c, scope) = controller(FakeRelayApi())
        val before = c.config.value
        assertEquals(USE_HTTPS, c.applySettings(before.toDraft().copy(relayUrl = "http://x.example.com")))
        assertEquals(NAME_REQUIRED, c.applySettings(before.toDraft().copy(deviceName = " ")))
        assertEquals(before, c.config.value)
        scope.cancel()
    }

    @Test fun saveReportsAnOsSettingThatFailed() = runTest {
        val (c, scope) = controller(FakeRelayApi(), launchAtLogin = { false })
        val error = c.applySettings(c.config.value.toDraft().copy(launchAtLogin = true))
        assertNotNull(error)
        assertFalse(c.config.value.launchAtLogin)
        scope.cancel()
    }

    @Test fun unpairRemovesNickname() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        c.refreshPeers()
        c.nicknames.set("ph", "Dad's phone")
        c.unpair(phone)
        assertEquals(listOf("ph"), fake.unpaired)
        assertNull(c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun failedUnpairKeepsNickname() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val api = object : RelayApi by fake {
            override suspend fun unpair(deviceId: String) = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        c.nicknames.set("ph", "Dad's phone")
        c.unpair(phone)
        assertEquals("Dad's phone", c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun renameNicknameUpdatesSendCards() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        c.refreshPeers()
        val peer = c.send.value.groups.single().peers.single()
        assertEquals("Pixel 8", c.nicknames.displayName(peer))
        c.nicknames.set("ph", "Dad's phone")
        assertEquals("Dad's phone", c.nicknames.displayName(c.send.value.groups.single().peers.single()))
        c.nicknames.set("ph", "")
        assertEquals("Pixel 8", c.nicknames.displayName(peer))
        scope.cancel()
    }

    @Test fun sendMessagesUseTheNickname() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        val notes = notes(c)
        c.nicknames.set("ph", "Dad's phone")
        assertEquals("Sent 1 tab to Dad's phone", c.send(phone, listOf(Tab("https://a.com")), emptyList()).message)
        assertTrue(notes.isEmpty())
        scope.cancel()
    }

    @Test fun relayStatusFollowsTheEventsStream() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val seen = mutableListOf<RelayStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.relayStatus.collect { seen += it } }
        runCurrent()
        assertEquals(RelayStatus.CONNECTING, c.relayStatus.value) // registered, nothing connected yet
        c.start(); runCurrent()
        assertEquals(RelayStatus.CONNECTING, c.relayStatus.value) // the stream is open but has not said Connected
        fake.events.emit(RelayEvent.Connected); runCurrent()
        assertEquals(RelayStatus.CONNECTED, c.relayStatus.value)
        fake.events.emit(RelayEvent.Disconnected); runCurrent()
        assertEquals(RelayStatus.CONNECTING, c.relayStatus.value)
        fake.events.emit(RelayEvent.Connected); runCurrent()
        assertEquals(RelayStatus.CONNECTED, c.relayStatus.value)
        assertEquals(listOf(RelayStatus.CONNECTING, RelayStatus.CONNECTED, RelayStatus.CONNECTING, RelayStatus.CONNECTED), seen)
        assertEquals("Relay connected", RelayStatus.CONNECTED.label)
        assertEquals("Connecting…", RelayStatus.CONNECTING.label)
        assertEquals("Relay not set", RelayStatus.NOT_SET.label)
        scope.cancel()
    }

    @Test fun relayStatusIsNotSetWithoutAValidAddress() = runTest {
        val (c, scope) = controller(FakeRelayApi(), config = """{"relayUrl":"","deviceName":"PC"}""")
        runCurrent()
        assertEquals(RelayStatus.NOT_SET, c.relayStatus.value)
        scope.cancel()
    }

    @Test fun relayStatusDropsToConnectingWhenTheServiceStops() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        c.start(); runCurrent()
        fake.events.emit(RelayEvent.Connected); runCurrent()
        assertEquals(RelayStatus.CONNECTED, c.relayStatus.value)
        c.changeRelay("https://b.example"); runCurrent()
        assertEquals(RelayStatus.CONNECTING, c.relayStatus.value) // the old stream is gone, the new one has not connected
        scope.cancel()
    }

    @Test fun sendResultIsNotifiedUnlessTheSendTabIsShowing() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        val notes = notes(c)
        c.mainWindowVisible = true
        c.mainWindowTab = MainTab.SEND
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com") }
        c.sendSelected().join()
        assertTrue(notes.isEmpty())
        c.mainWindowTab = MainTab.DEVICES // the window shows another tab: the result must still be seen
        c.updateSend { it.withText("https://b.com") }
        c.sendSelected().join()
        assertEquals(1, notes.size)
        assertNotNull(c.send.value.notice)
    }

    // ---- M5 fix round 1 ----------------------------------------------------------------------------------------

    @Test fun checkboxSaveWithTrailingSlashRelayDoesNotSwitch() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(
            fake, config = """{"relayUrl":"https://a.example/","deviceId":"old-id","token":"old-token","deviceName":"PC"}""",
        )
        val draft = c.config.value.toDraft().copy(openLinksOnArrival = false)
        assertFalse(draft.relayChanged(c.config.value))
        assertNull(c.applySettings(draft)); runCurrent()
        assertTrue(fake.registerCalls.isEmpty())
        assertEquals("old-token", c.config.value.token); assertEquals("old-id", c.config.value.deviceId)
        assertEquals("https://a.example/", c.config.value.relayUrl)
        assertFalse(c.config.value.openLinksOnArrival)
        scope.cancel()
    }

    @Test fun relayAndNameChangeWorksWhenOldRelayDown() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun rename(name: String): Peer = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        val draft = c.config.value.toDraft().copy(relayUrl = "https://b.example", deviceName = "Desk")
        assertNull(c.applySettings(draft)); runCurrent()
        assertEquals("Desk", c.config.value.deviceName)
        assertEquals("https://b.example", c.config.value.relayUrl)
        assertEquals("Desk", fake.registerCalls.last().first) // registered anew under the new name
        scope.cancel()
    }

    // ---- final review fixes ------------------------------------------------------------------------------------

    @Test fun secondLaunchWithoutPathsOpensTheWindowOnTheLastTab() = runTest {
        val (c, scope) = controller(FakeRelayApi())
        val opened = mutableListOf<MainTab>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.openRequests.collect { opened += it } }
        c.onLaunchedAgain(emptyList(), MainTab.RECEIVED)?.join()
        assertEquals(listOf(MainTab.RECEIVED), opened)
        assertTrue(c.send.value.files.isEmpty())
        scope.cancel()
    }

    @Test fun secondLaunchWithPathsQueuesThemOnTheSendTab() = runTest {
        val (c, scope) = controller(FakeRelayApi())
        val opened = mutableListOf<MainTab>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.openRequests.collect { opened += it } }
        c.onLaunchedAgain(listOf(File(dir, "one.txt").apply { writeText("1") }), MainTab.RECEIVED)?.join()
        assertEquals(listOf(MainTab.SEND), opened)
        assertEquals(1, c.send.value.files.size)
        scope.cancel()
    }

    @Test fun aSendInFlightBlocksASecondOne() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val gate = CompletableDeferred<Unit>()
        val api = object : RelayApi by fake {
            override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>, spaceId: String?): SendResponse { gate.await(); return fake.send(to, tabs, fileIds, spaceId) }
        }
        val (c, scope) = controller(api)
        c.refreshPeers()
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com") }
        val first = c.sendSelected(); runCurrent()
        assertTrue(c.send.value.sending)
        c.sendSelected().join() // ignored while the first runs
        gate.complete(Unit); first.join()
        assertEquals(1, fake.sent.size)
        assertFalse(c.send.value.sending)
        scope.cancel()
    }

    @Test fun remoteUnpairRemovesTheNickname() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        c.nicknames.set("ph", "Dad's phone")
        c.onUnpairedByPeer("ph")
        assertNull(c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun changingRelayClearsAllNicknames() = runTest {
        val (c, scope) = controller(FakeRelayApi())
        c.nicknames.set("ph", "Dad's phone")
        c.changeRelay("https://b.example"); runCurrent()
        assertTrue(c.nicknames.changes.value.isEmpty())
        scope.cancel()
    }

    @Test fun queuedFilesKeepTheRealNameAndNotifyWithTheNickname() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        c.nicknames.set("ph", "Dad's phone")
        val f = File(dir, "a.txt").apply { writeText("a") }
        val up = LocalUpload(f.absolutePath, "a.txt", "text/plain", 1)
        assertTrue("Dad's phone" in c.send(phone, emptyList(), listOf(up)).message)
        assertTrue("\"toName\":\"Pixel 8\"" in File(dir, "outbox.json").readText(), File(dir, "outbox.json").readText())
        scope.cancel()
    }

    @Test fun sendFromASpaceCardCarriesThatSpace() = runTest {
        val fred = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))
        val fake = FakeRelayApi().apply { peers += listOf(Peer("f", "Fred", DeviceKind.PC, SpaceRef("s1", "Friends")), fred) }
        val (c, scope) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.selectTarget(DestinationKey("f", "s2")).withText("https://a.com") }
        c.sendSelected().join()
        assertEquals(listOf("s2"), fake.sent.map { it.spaceId })
        assertEquals("f", c.config.value.lastPhoneId) // the persisted last-used stays the device id
        scope.cancel()
    }

    @Test fun spaceDeletedEventNotifiesAndRefreshes() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        c.onSpaceDeleted(SpaceRef("s1", "Friends")); runCurrent()
        assertEquals(listOf("Friends was deleted."), notes)
        scope.cancel()
    }

    @Test fun joinRequestForASpaceKeepsTheLastDeviceAndAcceptsOnce() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone, SpaceRef("s1", "Friends"))
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals(listOf("pair-1"), fake.accepted)
        assertNull(c.config.value.lastPhoneId) // joining a space is not a pairing
        assertTrue(notes.isEmpty())
        scope.cancel()
    }
}
