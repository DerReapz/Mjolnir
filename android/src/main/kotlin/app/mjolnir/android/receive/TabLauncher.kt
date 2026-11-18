package app.mjolnir.android.receive

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Browser
import android.widget.Toast
import androidx.core.net.toUri
import app.mjolnir.android.data.SavedFile
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.isSafeTabUrl

/** The URLs of [tabs] that may be opened (http/https only); checked again right before launching. */
fun safeUrls(tabs: List<Tab>): List<String> = tabs.map { it.url }.filter(::isSafeTabUrl)

private fun Intent.fromContext(context: Context): Intent =
    if (context is Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens every safe link in the browser, each in a new tab. */
fun openTabs(context: Context, tabs: List<Tab>) {
    for (url in safeUrls(tabs)) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
            .fromContext(context)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No browser: nothing to open it with.
        }
    }
}

/** Opens a received file in a viewer for its type; never anything but our own content:// URI. */
fun openFile(context: Context, file: SavedFile) {
    if (!file.uri.startsWith("content://")) return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri.toUri(), file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .fromContext(context)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open ${file.name}", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the system Downloads view (used for received folders). */
fun openDownloads(context: Context) {
    try {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).fromContext(context))
    } catch (e: ActivityNotFoundException) {
    }
}
