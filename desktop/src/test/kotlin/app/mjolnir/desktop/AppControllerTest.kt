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
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        c.nicknames.set("ph", "Dad's phone")
        c.refreshAfterSpaceChange() // what the unpaired event leads to: the relay no longer lists the device
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

    @Test fun fileSendFromASpaceCardNamesTheSpaceInTheNotification() = runTest {
        val fred = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))
        val fake = FakeRelayApi().apply { peers += fred }
        val (c, scope) = controller(fake)
        val f = File(dir, "a.txt").apply { writeText("a") }
        val up = LocalUpload(f.absolutePath, "a.txt", "text/plain", 1)
        val result = c.send(fred, listOf(Tab("https://a.com")), listOf(up))
        assertTrue("Sent 1 tab to Fred · Work" in result.message, result.message)
        assertTrue("\"spaceName\":\"Work\"" in File(dir, "outbox.json").readText(), File(dir, "outbox.json").readText())
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

    /** A relay that lists [phone] once, as paired when [paired], else under each of [spaceIds], like /v1/peers does. */
    private inner class DedupingRelay(var paired: Boolean, var spaceIds: Set<String>) : RelayApi by FakeRelayApi() {
        override suspend fun peers(): List<Peer> =
            if (paired) listOf(phone) else spaceIds.sorted().map { phone.copy(space = SpaceRef(it, "Space $it")) }
        override suspend fun unpair(deviceId: String) { paired = false }
    }

    @Test fun unpairKeepsNicknameWhileASharedSpaceLinksTheDevice() = runTest {
        val relay = DedupingRelay(paired = true, spaceIds = setOf("s1"))
        val (c, scope) = controller(relay)
        c.refreshPeers()
        assertNull(c.peers.value.single().space) // listed once, as paired
        c.nicknames.set("ph", "Dad's phone")
        c.unpair(phone)
        assertEquals(listOf("s1"), c.peers.value.map { it.space?.id }) // reappears as a space entry
        assertEquals("Dad's phone", c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun unpairRemovesNicknameWhenNothingElseLinks() = runTest {
        val relay = DedupingRelay(paired = true, spaceIds = emptySet())
        val (c, scope) = controller(relay)
        c.refreshPeers()
        c.nicknames.set("ph", "Dad's phone")
        c.unpair(phone)
        assertTrue(c.peers.value.isEmpty())
        assertNull(c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun remoteLeaveOrRemovalPrunesNicknameWhenNothingLinks() = runTest {
        val fake = FakeRelayApi()
        val relay = DedupingRelay(paired = false, spaceIds = setOf("s1"))
        val api = object : RelayApi by relay { override fun events() = fake.events }
        val (c, scope) = controller(api)
        c.start(); runCurrent()
        c.nicknames.set("ph", "Dad's phone")
        relay.spaceIds = emptySet() // the phone left, or was removed
        fake.events.emit(RelayEvent.SpaceChanged("s1")); runCurrent()
        assertTrue(c.peers.value.isEmpty())
        assertNull(c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun sharedSpaceKeepsNicknameAfterOtherSpaceIsDeleted() = runTest {
        val relay = DedupingRelay(paired = false, spaceIds = setOf("s1", "s2"))
        val (c, scope) = controller(relay)
        c.refreshPeers()
        c.nicknames.set("ph", "Dad's phone")
        relay.spaceIds = setOf("s1")
        c.onSpaceDeleted(SpaceRef("s2", "Space s2")); runCurrent()
        assertEquals("Dad's phone", c.nicknames.get("ph"))
        relay.spaceIds = emptySet()
        c.onSpaceDeleted(SpaceRef("s1", "Space s1")); runCurrent()
        assertNull(c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun ownDeleteShowsNoDeletedNotice() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        c.spaces.create("Friends"); runCurrent()
        val space = c.spaces.spaces.value.single()
        c.spaces.delete(space.id); runCurrent()
        c.onSpaceDeleted(SpaceRef(space.id, space.name)); runCurrent() // the relay's echo of our own delete
        assertTrue(notes.isEmpty())
        c.onSpaceDeleted(SpaceRef("other", "Other")); runCurrent() // someone else's delete is announced
        assertEquals(listOf("Other was deleted."), notes)
        scope.cancel()
    }

    @Test fun acceptAtTheMemberLimitSaysTheSpaceIsFull() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) = throw RelayException(RelayErrorKind.ALREADY_SENT, "member_limit")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone, SpaceRef("s1", "Friends"))
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals(listOf("This space is full (50 devices)."), notes)
        scope.cancel()
    }

    @Test fun acceptAtTheSpaceLimitSaysSo() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) = throw RelayException(RelayErrorKind.ALREADY_SENT, "space_limit")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone, SpaceRef("s1", "Friends"))
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals(listOf("You're in the most spaces allowed (50)."), notes)
        scope.cancel()
    }

    @Test fun failedPeerRefreshKeepsEveryNickname() = runTest {
        val relay = DedupingRelay(paired = false, spaceIds = setOf("s1"))
        var down = false
        val api = object : RelayApi by relay {
            override suspend fun peers(): List<Peer> = if (down) throw RelayException(RelayErrorKind.NETWORK, "offline") else relay.peers()
        }
        val (c, scope) = controller(api)
        c.refreshPeers()
        c.nicknames.set("ph", "Dad's phone")
        down = true
        assertFalse(c.refreshPeers())
        c.refreshAfterSpaceChange()
        assertEquals("Dad's phone", c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun emptyPeerListFromAFailedFirstRefreshDoesNotPrune() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun peers(): List<Peer> = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        c.nicknames.set("ph", "Dad's phone") // nothing loaded yet: _peers is still empty
        c.refreshAfterSpaceChange()
        assertEquals("Dad's phone", c.nicknames.get("ph"))
        scope.cancel()
    }

    @Test fun sendRemembersTheSpaceOfTheCardAndHandOffAimsAtIt() = runTest {
        val fake = FakeRelayApi().apply {
            peers += listOf(Peer("f", "Fred", DeviceKind.PC), Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work")))
        }
        val (c, scope) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.selectTarget(DestinationKey("f", "s2")).withText("https://a.com") }
        c.sendSelected().join()
        assertEquals("s2", c.config.value.lastSpaceId)
        c.updateSend { it.selectTarget(DestinationKey("f", null)) }
        c.handOff(emptyList()).join()
        assertEquals(DestinationKey("f", "s2"), c.send.value.targetKey)
        c.updateSend { it.selectTarget(DestinationKey("f", null)).withText("https://b.com") }
        c.sendSelected().join()
        assertNull(c.config.value.lastSpaceId) // a paired card clears it
        scope.cancel()
    }

    // files dropped on the Guest tab go to the guest through the outbox, show as YOU SENT, and never become the default target
    @Test fun sendToGuestListsTheFileAndMarksDownload() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val guest = Peer("g1", "Guest 4F2A", DeviceKind.GUEST)
        fake.peers += guest
        c.guestRoom.open(); runCurrent()
        c.guestRoom.onEvent(RelayEvent.GuestJoined((c.guestRoom.state.value as GuestRoomUi.Open).roomId, guest))
        val file = File(dir, "Seating plan.pdf").apply { writeText("pdf") }

        c.sendToGuest(listOf(file)).join()
        repeat(200) { runCurrent(); if (fake.sent.isEmpty()) Thread.sleep(10) } // the upload reads the file on a real IO thread

        val row = c.guestRoom.exchanged.value.single()
        assertEquals("Seating plan.pdf", row.title); assertFalse(row.fromGuest); assertFalse(row.downloadedByGuest)
        assertNull(c.config.value.lastPhoneId)
        val fileId = fake.sent.single().fileIds.single()
        c.onGuestRoomEvent(RelayEvent.Downloaded("d1", listOf(fileId)))
        assertTrue(c.guestRoom.exchanged.value.single().downloadedByGuest)
        scope.cancel()
    }

    private suspend fun TestScope.openRoomWithGuest(c: AppController, fake: FakeRelayApi): Peer {
        val guest = Peer("g1", "Guest 4F2A", DeviceKind.GUEST)
        fake.peers += guest
        c.guestRoom.open(); runCurrent()
        return guest
    }

    private fun roomId(c: AppController) = (c.guestRoom.state.value as GuestRoomUi.Open).roomId

    @Test fun guestJoiningNotifiesOnceAndOnlyForTheOpenRoom() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        val guest = openRoomWithGuest(c, fake)
        c.onGuestRoomEvent(RelayEvent.GuestJoined("some-other-room", guest))
        assertEquals(emptyList(), notes)
        c.onGuestRoomEvent(RelayEvent.GuestJoined(roomId(c), guest))
        c.onGuestRoomEvent(RelayEvent.GuestJoined(roomId(c), guest))
        assertEquals(listOf("Guest joined"), notes)
        scope.cancel()
    }

    @Test fun linksSentToTheGuestGetAYouSentRowEach() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val guest = openRoomWithGuest(c, fake)
        c.guestRoom.onEvent(RelayEvent.GuestJoined(roomId(c), guest))
        c.send(guest, listOf(Tab("https://a.example", "A"), Tab("https://b.example")), emptyList())
        runCurrent()
        val rows = c.guestRoom.exchanged.value
        assertEquals(listOf("A", "https://b.example"), rows.map { it.title })
        assertTrue(rows.all { !it.fromGuest && it.detail == "Link" })
        scope.cancel()
    }

    @Test fun linksSentToADeviceAreNotListedInTheRoom() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        val guest = openRoomWithGuest(c, fake)
        c.guestRoom.onEvent(RelayEvent.GuestJoined(roomId(c), guest))
        c.send(phone, listOf(Tab("https://a.example")), emptyList())
        runCurrent()
        assertEquals(emptyList(), c.guestRoom.exchanged.value)
        scope.cancel()
    }

    @Test fun failedSendToTheGuestEndsItsRowWithoutProgress() = runTest {
        val fake = FakeRelayApi().apply { failUploadsWith = app.mjolnir.client.RelayException(app.mjolnir.client.RelayErrorKind.TOO_LARGE, "too big") }
        val (c, scope) = controller(fake)
        val guest = openRoomWithGuest(c, fake)
        c.guestRoom.onEvent(RelayEvent.GuestJoined(roomId(c), guest))
        val file = File(dir, "big.bin").apply { writeText("data") }
        c.sendToGuest(listOf(file)).join()
        repeat(200) { runCurrent(); if (c.guestRoom.exchanged.value.singleOrNull()?.detail != "Couldn't send") Thread.sleep(10) }
        val row = c.guestRoom.exchanged.value.single()
        assertEquals("Couldn't send", row.detail)
        assertNull(row.progress)
        assertEquals("big.bin", row.title)
        scope.cancel()
    }

    // ---- chats ----

    private val fred = Peer("fred", "Fred's phone", DeviceKind.PHONE)
    private val chatConfig get() = """{"relayUrl":"https://a.example","deviceId":"dev-1","token":"tok-1","deviceName":"PC","saveDir":"${File(dir, "save").path.replace("\\", "\\\\")}"}"""

    /** A relay where this device and Fred are in the chat "Game night" of the space "Friends". */
    private fun chatRelay(): Pair<FakeRelayApi, String> {
        val fake = FakeRelayApi()
        fake.asMemberOf("s1", Peer("admin", "Admin", DeviceKind.PC), name = "Friends")
        fake.addMember("s1", fred)
        return fake to fake.addChat("s1", "Game night", owner = fake.me, members = listOf(fake.me, fred))
    }

    @Test fun newMessageNotifiesWhenChatNotOpen() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        c.chats.refresh(); runCurrent()
        fake.incoming(chat, fred, "who's bringing snacks?")
        c.chats.sync(chat); runCurrent()
        assertEquals(listOf("Fred's phone in Game night: who's bringing snacks?"), notes)
        scope.cancel()
    }

    @Test fun notificationNamesTheAuthorByNickname() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        c.nicknames.set("fred", "Freddie")
        c.chats.refresh(); runCurrent()
        fake.incoming(chat, fred, "hi")
        c.chats.sync(chat); runCurrent()
        assertEquals(listOf("Freddie in Game night: hi"), notes)
        scope.cancel()
    }

    @Test fun noNotificationForOpenChat() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        c.chats.refresh(); runCurrent()
        c.chats.openChat.value = chat
        c.mainWindowVisible = true; c.mainWindowTab = MainTab.CHATS
        fake.incoming(chat, fred, "who's bringing snacks?")
        c.chats.sync(chat); runCurrent()
        assertEquals(emptyList(), notes)
        scope.cancel()
    }

    // the open chat only silences the tray while the window is showing the Chats tab
    @Test fun openChatInAHiddenOrOtherTabWindowStillNotifies() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        val m = IncomingMessage(chat, "Game night", "fred", "Fred's phone", "hi")
        c.chats.openChat.value = chat
        c.mainWindowVisible = false; c.mainWindowTab = MainTab.CHATS
        c.onIncomingChatMessage(m)
        c.mainWindowVisible = true; c.mainWindowTab = MainTab.SEND
        c.onIncomingChatMessage(m)
        c.mainWindowTab = MainTab.CHATS
        c.onIncomingChatMessage(m)
        assertEquals(2, notes.size)
        scope.cancel()
    }

    @Test fun sameNamedDevicesGetTheirOwnNickname() = runTest {
        val (fake, chat) = chatRelay()
        val twin = Peer("fred2", "Fred's phone", DeviceKind.PHONE)
        fake.addMember("s1", twin)
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        c.nicknames.set("fred", "Fred A"); c.nicknames.set("fred2", "Fred B")
        c.chats.refresh(); runCurrent()
        c.onIncomingChatMessage(IncomingMessage(chat, "Game night", "fred2", "Fred's phone", "hi"))
        assertEquals(listOf("Fred B in Game night: hi"), notes)
        scope.cancel()
    }

    @Test fun reRegisterClearsChats() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        c.chats.refresh(); fake.incoming(chat, fred, "hi"); c.chats.sync(chat); runCurrent()
        c.selectChat(chat); c.chats.openChat.value = chat
        assertTrue(File(dir, "chats").listFiles()!!.isNotEmpty())
        fake.failAllWith = RelayException(RelayErrorKind.UNAUTHORIZED, "unauthorized")
        c.refreshPeers()
        fake.failAllWith = null
        runCurrent()
        assertEquals(emptyList(), c.chats.chats.value)
        assertEquals(emptyList(), c.chats.messages(chat).value)
        assertEquals(emptyList(), File(dir, "chats").listFiles()!!.toList())
        assertNull(c.selectedChat.value); assertNull(c.chats.openChat.value)
        scope.cancel()
    }

    @Test fun changeRelayClearsChats() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        c.chats.refresh(); c.selectChat(chat); runCurrent()
        c.changeRelay("https://b.example"); runCurrent()
        assertEquals(emptyList(), c.chats.chats.value); assertNull(c.selectedChat.value)
        scope.cancel()
    }

    @Test fun minimisedWindowCountsAsNotVisible() = runTest {
        val (fake, chat) = chatRelay()
        val (c, scope) = controller(fake, config = chatConfig)
        val notes = notes(c)
        val m = IncomingMessage(chat, "Game night", "fred", "Fred's phone", "hi")
        c.chats.openChat.value = chat; c.mainWindowTab = MainTab.CHATS
        c.mainWindowVisible = true
        c.onIncomingChatMessage(m); assertEquals(0, notes.size)
        c.setWindowMinimized(true)
        assertFalse(c.mainWindowVisible); assertFalse(c.windowShowing.value)
        c.onIncomingChatMessage(m); assertEquals(1, notes.size)
        c.setWindowMinimized(false)
        assertTrue(c.windowShowing.value)
        c.mainWindowVisible = false; c.setWindowMinimized(true); c.setWindowMinimized(false)
        assertFalse(c.windowShowing.value) // restoring does not show a window that is hidden
        scope.cancel()
    }
}
