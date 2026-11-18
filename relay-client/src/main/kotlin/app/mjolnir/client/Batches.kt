package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Tab

data class SendOutcome(val sentTabs: List<Tab>, val sentFiles: List<FileRef>, val error: RelayException?)

/** Tabs go in batches of 100, then files in batches of 1000; a delivery never mixes the two. Stops at the first error. */
suspend fun sendInBatches(api: RelayApi, to: String, tabs: List<Tab>, files: List<FileRef> = emptyList()): SendOutcome {
    val sentTabs = mutableListOf<Tab>()
    val sentFiles = mutableListOf<FileRef>()
    try {
        for (batch in tabs.chunked(Limits.MAX_TABS_PER_SEND)) {
            api.send(to, batch)
            sentTabs += batch
        }
        for (batch in files.chunked(Limits.MAX_FILES_PER_SEND)) {
            api.send(to, emptyList(), batch.map { it.id })
            sentFiles += batch
        }
    } catch (e: RelayException) {
        return SendOutcome(sentTabs, sentFiles, e)
    }
    return SendOutcome(sentTabs, sentFiles, null)
}
