package dev.omalauncher.widgets

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.httpGet
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapEntries
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import dev.omalauncher.widgets.market.Review
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widget marketplace: catalogs published from GitHub repos to GitHub Pages
 * ([Store]s), the reviewed built-in one and any the user adds. Installs are
 * checked against the catalog's SHA-256, land on the current page, and remember
 * their store and which version and commands were approved, so an update that
 * changes them asks again.
 */
class Market(
    private val engine: WidgetEngine,
) {
    companion object {
        private const val VERSION = 1
        private const val STORES_VERSION = 1
    }

    /** What the user installed and approved, per [installKey]. */
    data class Installed(
        val id: String,
        val file: String,
        val version: String,
        val commands: List<String>,
        val requires: Map<String, List<String>>,
    )

    /** The built-in store first, then the ones the user added. */
    var stores by mutableStateOf(listOf(Store.BUILT_IN))
        private set

    /** The store [entries] come from. */
    var current by mutableStateOf(Store.BUILT_IN)
        private set
    var entries by mutableStateOf<List<CatalogEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val working = mutableStateMapOf<String, String>()
    val installed = mutableStateMapOf<String, Installed>()
    private val previews = HashMap<String, ImageBitmap?>()
    private val record get() = File(engine.dir, ".market.json")
    private val storesRecord get() = File(engine.dir, ".stores.json")

    suspend fun refresh() {
        val store = current
        loading = true
        error = null
        try {
            val list = withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(store.base + "index.json"))) }
            // The user may have picked another store meanwhile.
            if (current == store) entries = list
        } catch (e: Exception) {
            if (current == store) error = "couldn't load the store: ${e.message ?: e}"
        } finally {
            if (current == store) loading = false
        }
    }

    /** Shows [store]'s widgets once refreshed. */
    fun select(store: Store) {
        current = store
        entries = emptyList()
        error = null
        saveStores()
    }

    /** Adds and selects the store [input] names, once its catalog loads. Returns an error, or null. */
    suspend fun addStore(input: String): String? {
        val store = Store.parse(input) ?: return "use owner/repo or the store's URL"
        if (store in stores) return "${store.repo} is already in your stores"
        val list =
            try {
                withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(store.base + "index.json"))) }
            } catch (e: Exception) {
                return "no store at ${store.base}: ${e.message ?: e}"
            }
        stores = stores + store
        select(store)
        entries = list
        return null
    }

    /** Forgets a store the user added; what was installed from it stays. */
    fun removeStore(store: Store) {
        if (store.reviewed) return
        stores = stores - store
        if (current == store) select(Store.BUILT_IN) else saveStores()
    }

    private fun installedFrom(
        store: Store,
        e: CatalogEntry,
    ) = installed[installKey(store, e.id)]?.takeIf { File(engine.dir, it.file).exists() }

    /** Installed from the current store, and its file is still there (removing the tile moves the file away). */
    fun installedVersion(e: CatalogEntry): Installed? = installedFrom(current, e)

    fun hasUpdate(e: CatalogEntry) = installedVersion(e)?.let { Catalog.compareVersions(e.version, it.version) > 0 } == true

    /** An update runs different commands or needs other packages than what the user approved. */
    fun changedSinceApproval(e: CatalogEntry) = installedVersion(e)?.let { it.commands != e.commands || it.requires != e.requires } == true

    /** What's happening to [e] from the current store, like "installing…", or null. */
    fun busy(e: CatalogEntry) = working[installKey(current, e.id)]

    /** Downloads, verifies and installs [e] from the current store on [page]; its packages install right after. Returns an error, or null. */
    suspend fun install(
        e: CatalogEntry,
        page: Int,
    ): String? {
        val store = current
        val key = installKey(store, e.id)
        working[key] = "installing…"
        try {
            val bytes = withContext(Dispatchers.IO) { httpGet(store.base + e.path) }
            if (Review.sha256(bytes) != e.sha256) return "the download doesn't match the store's catalog; try again later"
            val update = installedFrom(store, e)
            val name = update?.file ?: installFile(store, e.author, e.id.substringAfter('/'))
            withContext(Dispatchers.IO) {
                // An update keeps its page; a new install goes where the user is.
                if (update == null) engine.setPage(name, page)
                engine.installDependenciesWhenLoaded(name)
                File(engine.dir, name).writeBytes(bytes)
            }
            installed[key] = Installed(key, name, e.version, e.commands, e.requires)
            save()
            return null
        } catch (x: Exception) {
            return "install failed: ${x.message ?: x}"
        } finally {
            working.remove(key)
        }
    }

    /** [e]'s preview drawn in [theme] when the store has one, else its default preview. */
    suspend fun preview(
        e: CatalogEntry,
        theme: String?,
    ): ImageBitmap? {
        // By URL: two stores can have the same paths.
        val url = current.base + (e.previewFor(theme) ?: return null)
        synchronized(previews) { if (url in previews) return previews[url] }
        val img =
            withContext(Dispatchers.IO) {
                runCatching { httpGet(url).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
            }
        synchronized(previews) { previews[url] = img }
        return img
    }

    /** Reads the stores and install records; call off the main thread before the store opens. */
    fun load() {
        (Versioned.read(storesRecord.takeIf { it.exists() }?.readText(), STORES_VERSION) as? JSONObject)?.let { data ->
            stores = listOf(Store.BUILT_IN) + data.optJSONArray("stores")?.strings().orEmpty().mapNotNull { Store.parse(it) }.filterNot { it.reviewed }.distinct()
            current = Store.parse(data.optString("current"))?.takeIf { it in stores } ?: Store.BUILT_IN
        }
        // Version 0 (before versioning) is the same object, unwrapped.
        val data = Versioned.read(record.takeIf { it.exists() }?.readText(), VERSION) { _, d -> d } as? JSONObject ?: return
        installed.putAll(
            data.mapEntries { id, f ->
                Installed(
                    id = id,
                    file = f.getString("file"),
                    version = f.getString("version"),
                    commands = f.optJSONArray("commands")?.strings().orEmpty(),
                    requires = f.optJSONObject("requires")?.let { r -> r.keys().asSequence().associateWith { r.optJSONArray(it)?.strings().orEmpty() } }.orEmpty(),
                )
            },
        )
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun save() {
        val data = JSONObject()
        for (i in installed.values) {
            data.put(
                i.id,
                JSONObject()
                    .put("file", i.file)
                    .put("version", i.version)
                    .put("commands", JSONArray(i.commands))
                    .put("requires", JSONObject(i.requires.mapValues { JSONArray(it.value) } as Map<*, *>)),
            )
        }
        runCatching { writeAtomically(record, Versioned.write(VERSION, data).toByteArray()) }
    }

    /** The stores the user added, and the one picked last. */
    private fun saveStores() {
        val data = JSONObject().put("stores", JSONArray(stores.filterNot { it.reviewed }.map { it.repo })).put("current", current.repo)
        runCatching { writeAtomically(storesRecord, Versioned.write(STORES_VERSION, data).toByteArray()) }
    }
}
