package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.Outbox
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Tab
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThrowViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private val api = FakeRelayApi().apply {
        peers += listOf(Peer("pc1", "DESKTOP-4F2K", DeviceKind.PC), Peer("pc2", "Laptop", DeviceKind.PC), Peer("ph", "Other phone", DeviceKind.PHONE))
    }
    private val scheduled = mutableListOf<String>()
    private lateinit var trayStore: JsonFileStore<List<TrayItem>>
    private lateinit var configStore: JsonFileStore<PhoneConfig>
    private lateinit var outbox: Outbox
    private lateinit var vm: ThrowViewModel
    private lateinit var nicknames: Nicknames

    private val a = TrayItem.Link(Tab("https://a.com"))
    private val b = TrayItem.Link(Tab("https://b.com"))
    private val c = TrayItem.Link(Tab("https://c.com"))
    private lateinit var f: TrayItem.LocalFile
    private val photos = TrayItem.Folder("photos", "Photos", listOf(upload("Photos/a.jpg"), upload("Photos/b.jpg")))

    private fun upload(relativePath: String) = LocalUpload("content://tree/$relativePath", relativePath, "image/jpeg", 1)
    private fun folder(id: String, name: String, n: Int) = TrayItem.Folder(id, name, (1..n).map { upload("$name/$it.jpg") })

    @Before fun setUp() {
        trayStore = JsonFileStore(File(temp.root, "tray.json"), ListSerializer(TrayItem.serializer())) { emptyList() }
        configStore = JsonFileStore(File(temp.root, "config.json"), PhoneConfig.serializer()) { PhoneConfig("https://relay.example", deviceName = "Pixel") }
        outbox = Outbox(
            JsonFileStore(File(temp.root, "outbox.json"), ListSerializer(OutgoingFiles.serializer())) { emptyList() },
            api = { api },
            open = { u, offset -> File(u.path).inputStream().also { it.skip(offset) } },
        )
        val copy = File(temp.newFolder("outbox-files"), "f-copy").apply { writeText("hello") }
        f = TrayItem.LocalFile("f", copy.path, "f.txt", 5, "text/plain")
        nicknames = Nicknames(JsonFileStore(File(temp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
        vm = newVm()
    }

    private fun newVm() = ThrowViewModel(api, trayStore, configStore, outbox, nicknames, { scheduled += it }, main.dispatcher)

    private fun throwNow() { vm.onHoldStart(); vm.onFlick() }

    @Test fun targetsShowNickname() = runTest {
        nicknames.set("pc1", "Work PC")
        vm.refreshTargets(); advanceUntilIdle()
        assertEquals(listOf("Work PC", "Laptop", "Other phone"), vm.state.value.targets.map { it.name })
        assertEquals("pc1", vm.state.value.targetId)
    }

    @Test fun groupsSingleGroupNoTitle() = runTest {
        nicknames.set("ph", "Aaa")
        vm.refreshTargets(); advanceUntilIdle()
        val groups = vm.state.value.groups
        assertEquals(1, groups.size)
        assertNull(groups.single().title)
        assertEquals(listOf("pc1", "ph", "pc2"), groups.single().peers.map { it.deviceId }.let { it }) // last used first, then by shown name
    }

    @Test fun sharedItemsLandInTrayAndAreSelected() = runTest {
        vm.addShared(listOf(a, f)); advanceUntilIdle()
        assertEquals(listOf(a, f), vm.state.value.tray)
        assertEquals(setOf(a.id, f.id), vm.state.value.selected)
    }

    @Test fun sharingNothingUsefulShowsNotice() = runTest {
        vm.addShared(emptyList()); advanceUntilIdle()
        assertEquals("No links found in what you shared", vm.state.value.notice)
    }

    @Test fun sharingNoticesAreJoinedByLines() = runTest {
        vm.addShared(emptyList(), listOf("x is too big (max 100 MB)", "Couldn't read y")); advanceUntilIdle()
        assertEquals("x is too big (max 100 MB)\nCouldn't read y", vm.state.value.notice)
        vm.clearNotice()
        assertNull(vm.state.value.notice)
    }

    @Test fun sharingAKnownLinkAgainDoesNotDuplicateIt() = runTest {
        vm.addShared(listOf(a)); vm.addShared(listOf(a, b)); advanceUntilIdle()
        assertEquals(listOf(a, b), vm.state.value.tray)
    }

    @Test fun trayPersistsAcrossViewModels() = runTest {
        vm.addShared(listOf(a, f, photos)); vm.toggle(a.id); advanceUntilIdle()
        val again = newVm(); advanceUntilIdle()
        assertEquals(listOf(a, f, photos), again.state.value.tray)
    }

    @Test fun sharingBeforeTheTrayLoadsKeepsBothOldAndNew() = runTest {
        trayStore.write(listOf(a))
        val fresh = newVm(); fresh.addShared(listOf(b)); advanceUntilIdle()
        assertEquals(listOf(a, b), fresh.state.value.tray)
        assertEquals(listOf(a, b), trayStore.read())
    }

    @Test fun phonesAreThrowTargetsToo() = runTest {
        vm.refreshTargets(); advanceUntilIdle()
        assertEquals(listOf("pc1", "pc2", "ph"), vm.state.value.targets.map { it.deviceId })
    }

    @Test fun throwToAPhoneSendsToItsId() = runTest {
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle()
        vm.selectTarget("ph"); advanceUntilIdle()
        vm.onHoldStart(); vm.onFlick(); advanceUntilIdle()
        assertEquals(listOf(SendRequest("ph", listOf(a.tab))), api.sent)
        assertEquals("Thrown 1 tab to Other phone", vm.state.value.notice)
        assertEquals("ph", configStore.read().targetPcId)
    }

    @Test fun targetDefaultsToRememberedPcOrFirstPc() = runTest {
        configStore.write(configStore.read().copy(targetPcId = "pc2"))
        val remembered = newVm(); remembered.refreshTargets(); advanceUntilIdle()
        assertEquals("pc2", remembered.state.value.targetId)
        configStore.write(configStore.read().copy(targetPcId = "gone"))
        val fallback = newVm(); fallback.refreshTargets(); advanceUntilIdle()
        assertEquals("pc1", fallback.state.value.targetId)
    }

    @Test fun selectedTargetIsRemembered() = runTest {
        vm.refreshTargets(); advanceUntilIdle()
        vm.selectTarget("pc2"); advanceUntilIdle()
        assertEquals("pc2", vm.state.value.targetId)
        assertEquals("pc2", configStore.read().targetPcId)
    }

    @Test fun throwSendsSelectedLinksToTargetAndClearsThem() = runTest {
        vm.addShared(listOf(a, b, c)); vm.toggle(b.id); vm.refreshTargets(); advanceUntilIdle()
        vm.onHoldStart(); vm.onFlick(); advanceUntilIdle()
        assertEquals(listOf(SendRequest("pc1", listOf(a.tab, c.tab))), api.sent)
        assertEquals(listOf(b), vm.state.value.tray); assertEquals("Thrown 2 tabs to DESKTOP-4F2K", vm.state.value.notice)
        assertEquals(listOf(b), trayStore.read())
        assertEquals(ThrowPhase.Idle, vm.state.value.phase)
    }

    @Test fun throwQueuesFilesAndFoldersInTheOutbox() = runTest {
        vm.addShared(listOf(a, f, photos)); vm.refreshTargets(); advanceUntilIdle(); vm.onHoldStart(); vm.onFlick(); advanceUntilIdle()
        assertEquals(listOf(SendRequest("pc1", listOf(a.tab))), api.sent)
        val queued = outbox.pending().single()
        assertEquals(listOf("f.txt", "Photos/a.jpg", "Photos/b.jpg"), queued.files.map { it.relativePath })
        assertEquals("pc1", queued.to); assertEquals("DESKTOP-4F2K", queued.toName)
        assertEquals(listOf(queued.id), scheduled); assertTrue(vm.state.value.tray.isEmpty()); assertTrue(File(f.path).exists())
        assertEquals("Thrown 1 tab to DESKTOP-4F2K. Throwing 3 files to DESKTOP-4F2K in the background", vm.state.value.notice)
        assertEquals(listOf(queued), vm.state.value.outgoing)
    }

    @Test fun unauthorizedThrowResetsTheSessionAndSaysSo() = runTest {
        var resets = 0
        vm = ThrowViewModel(api, trayStore, configStore, outbox, nicknames, { scheduled += it }, main.dispatcher,
            onUnauthorized = { resets++; throw java.io.IOException("disk full") })
        api.onSend = { throw RelayException(RelayErrorKind.UNAUTHORIZED, "x") }
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle(); throwNow(); advanceUntilIdle()
        assertEquals(1, resets)
        assertEquals(app.mjolnir.client.Messages.RESET, vm.state.value.notice)
        assertEquals(listOf(a), vm.state.value.tray)
    }

    @Test fun offlineThrowKeepsTabsAndQueuesFiles() = runTest {
        api.onSend = { throw RelayException(RelayErrorKind.NETWORK, "down") }
        vm.addShared(listOf(a, f)); vm.refreshTargets(); advanceUntilIdle(); vm.onHoldStart(); vm.onFlick(); advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.tray); assertEquals(setOf(a.id), vm.state.value.selected)
        assertEquals(1, outbox.pending().size)
        assertEquals("Not thrown: no connection. Your tabs are still here. Throwing 1 file to DESKTOP-4F2K in the background", vm.state.value.notice)
    }

    @Test fun over1000FilesAreSplitWithoutSplittingFolders() = runTest {
        vm.addShared(listOf(folder("p1", "One", 600), folder("p2", "Two", 600))); vm.refreshTargets(); advanceUntilIdle()
        throwNow(); advanceUntilIdle()
        assertEquals(listOf(600, 600), outbox.pending().map { it.files.size })
        assertEquals(2, scheduled.size)
        assertEquals("Throwing 1200 files to DESKTOP-4F2K in the background", vm.state.value.notice)
    }

    @Test fun smallItemsShareOneOutgoingItem() = runTest {
        vm.addShared(listOf(folder("p1", "One", 600), f, folder("p2", "Two", 399))); vm.refreshTargets(); advanceUntilIdle()
        throwNow(); advanceUntilIdle()
        assertEquals(listOf(1000), outbox.pending().map { it.files.size })
    }

    @Test fun removingAFileDeletesItsOutboxCopy() = runTest {
        vm.addShared(listOf(a, f)); advanceUntilIdle()
        vm.remove(f.id); advanceUntilIdle()
        assertEquals(false, File(f.path).exists())
        assertEquals(listOf(a), vm.state.value.tray); assertEquals(setOf(a.id), vm.state.value.selected)
        assertEquals(listOf(a), trayStore.read())
    }

    @Test fun flickWithoutHoldDoesNothing() = runTest {
        vm.addShared(listOf(a, f)); vm.refreshTargets(); advanceUntilIdle()
        vm.onFlick(); advanceUntilIdle()
        assertTrue(api.sent.isEmpty()); assertTrue(outbox.pending().isEmpty())
    }

    @Test fun releasingTheHoldDisarms() = runTest {
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle()
        vm.onHoldStart(); assertEquals(ThrowPhase.Armed, vm.state.value.phase)
        vm.onHoldEnd(); assertEquals(ThrowPhase.Idle, vm.state.value.phase)
        vm.onFlick(); advanceUntilIdle()
        assertTrue(api.sent.isEmpty())
    }

    @Test fun holdIsIgnoredWithoutTargetOrSelection() = runTest {
        api.peers.clear()
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle()
        vm.onHoldStart()
        assertEquals(ThrowPhase.Idle, vm.state.value.phase)
        api.peers += Peer("pc1", "DESKTOP-4F2K", DeviceKind.PC)
        vm.refreshTargets(); vm.toggle(a.id); advanceUntilIdle()
        vm.onHoldStart()
        assertEquals(ThrowPhase.Idle, vm.state.value.phase)
    }

    @Test fun enqueueFailureShowsNoticeAndKeepsFilesInTray() = runTest {
        val blocker = temp.newFile("not-a-dir")
        val brokenOutbox = Outbox(
            JsonFileStore(File(blocker, "outbox.json"), ListSerializer(OutgoingFiles.serializer())) { emptyList() },
            api = { api },
            open = { u, offset -> File(u.path).inputStream().also { it.skip(offset) } },
        )
        val broken = ThrowViewModel(api, trayStore, configStore, brokenOutbox, nicknames, { scheduled += it }, main.dispatcher)
        broken.refreshTargets(); broken.addShared(listOf(f), listOf("stale notice")); advanceUntilIdle()
        broken.onHoldStart(); broken.onFlick(); advanceUntilIdle()
        assertEquals("Not thrown: this phone couldn't queue the files. Your tabs are still here.", broken.state.value.notice)
        assertEquals(listOf<TrayItem>(f), broken.state.value.tray)
        assertTrue(scheduled.isEmpty())
    }

    @Test fun importSharedAddsLinksAndImportedFilesAndNotices() = runTest {
        vm.importShared(listOf(Tab("https://a.com")), { app.mjolnir.android.share.Imported(listOf(f), listOf("big.mp4 is too big (max 100 MB)")) })
        advanceUntilIdle()
        assertEquals(listOf<TrayItem>(a, f), vm.state.value.tray)
        assertEquals(setOf(a.id, f.id), vm.state.value.selected)
        assertEquals("big.mp4 is too big (max 100 MB)", vm.state.value.notice)
    }

    @Test fun importSharedWithNothingUsableSaysSo() = runTest {
        vm.importShared(emptyList(), null); advanceUntilIdle()
        assertEquals("No links found in what you shared", vm.state.value.notice)
    }

    @Test fun importSharedSurvivesARevokedGrant() = runTest {
        vm.importShared(listOf(Tab("https://a.com")), { throw SecurityException("grant revoked") })
        advanceUntilIdle()
        assertEquals(listOf<TrayItem>(a), vm.state.value.tray)
        assertEquals("Couldn't read what you shared", vm.state.value.notice)
    }

    @Test fun importSharedSurvivesAnyImporterFailure() = runTest {
        vm.importShared(emptyList(), { throw IllegalStateException("provider crashed") })
        advanceUntilIdle()
        assertTrue(vm.state.value.tray.isEmpty())
        assertEquals("Couldn't read what you shared", vm.state.value.notice)
    }

    @Test fun filesReturnedInTheBackgroundShowUpAndSurviveTrayWrites() = runTest {
        vm.addShared(listOf(a)); advanceUntilIdle()
        trayStore.update { it + photos } // the background worker gave a failed throw back
        vm.addShared(listOf(b)); advanceUntilIdle() // the view model writes its tray
        assertEquals(listOf(a, photos, b).map { it.id }.toSet(), trayStore.read().map { it.id }.toSet())
        vm.refreshOutgoing(); advanceUntilIdle()
        assertEquals(listOf(a, b, photos), vm.state.value.tray)
    }

    @Test fun aReturnedItemThatIsRemovedStaysGone() = runTest {
        vm.addShared(listOf(a)); advanceUntilIdle()
        trayStore.update { it + photos }
        vm.refreshOutgoing(); advanceUntilIdle()
        assertEquals(listOf(a, photos), vm.state.value.tray)
        vm.remove(photos.id); advanceUntilIdle()
        vm.refreshOutgoing(); advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.tray)
        assertEquals(listOf(a), trayStore.read())
    }

    @Test fun aReturnedItemThatIsThrownIsNotThrownTwice() = runTest {
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle()
        trayStore.update { it + photos } // the background worker gave a failed throw back
        vm.refreshOutgoing(); advanceUntilIdle()
        vm.toggle(a.id) // throw only the returned folder
        throwNow(); advanceUntilIdle()
        assertEquals(1, outbox.pending().size)
        vm.refreshOutgoing(); advanceUntilIdle()
        assertEquals(listOf(a), vm.state.value.tray)
        assertEquals(listOf(a), trayStore.read())
        vm.toggle(a.id); throwNow(); advanceUntilIdle()
        assertEquals(1, outbox.pending().size) // the folder was not queued again
    }

    @Test fun removedItemsAreNotBroughtBackByARefresh() = runTest {
        vm.addShared(listOf(a, b)); advanceUntilIdle()
        vm.remove(a.id); advanceUntilIdle()
        vm.refreshOutgoing(); advanceUntilIdle()
        assertEquals(listOf(b), vm.state.value.tray)
        assertEquals(listOf(b), trayStore.read())
    }

    @Test fun refreshSensitivityReadsSettings() = runTest {
        configStore.write(configStore.read().copy(sensitivity = app.mjolnir.android.throwing.Sensitivity.HIGH))
        vm.refreshSensitivity(); advanceUntilIdle()
        assertEquals(app.mjolnir.android.throwing.Sensitivity.HIGH, vm.state.value.sensitivity)
    }

    @Test fun secondFlickWhileThrowingIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.onSend = { gate.await(); SendResponse("d1") }
        vm.addShared(listOf(a)); vm.refreshTargets(); advanceUntilIdle()
        throwNow(); advanceUntilIdle()
        assertEquals(ThrowPhase.Throwing, vm.state.value.phase)
        vm.onHoldStart(); vm.onFlick(); advanceUntilIdle()
        assertEquals(1, api.sent.size)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.sent.size); assertEquals(ThrowPhase.Idle, vm.state.value.phase)
    }

    @Test fun throwOf250TabsGoesInBatches() = runTest {
        vm.addShared((1..250).map { TrayItem.Link(Tab("https://a.com/$it")) }); vm.refreshTargets(); advanceUntilIdle()
        throwNow(); advanceUntilIdle()
        assertEquals(listOf(100, 100, 50), api.sent.map { it.tabs.size })
        assertTrue(vm.state.value.tray.isEmpty())
        assertEquals("Thrown 250 tabs to DESKTOP-4F2K", vm.state.value.notice)
    }

    @Test fun batchFailureKeepsOnlyTheUnsentTabs() = runTest {
        api.onSend = { if (api.sent.size > 1) throw RelayException(RelayErrorKind.RATE_LIMITED, "slow down") else SendResponse("d") }
        vm.addShared((1..150).map { TrayItem.Link(Tab("https://a.com/$it")) }); vm.refreshTargets(); advanceUntilIdle()
        throwNow(); advanceUntilIdle()
        assertEquals(50, vm.state.value.tray.size)
        assertEquals("Thrown 100 tabs to DESKTOP-4F2K. Not thrown: too many throws, wait a minute. Your tabs are still here.", vm.state.value.notice)
    }
}
