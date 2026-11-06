package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.sha256Hex
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.R
import dev.omalauncher.httpGet
import dev.omalauncher.ui.decodeImageIcon
import dev.omalauncher.ui.decodeSvgIcon
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapEntries
import dev.omalauncher.upgrade.strings
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** What the store says about an entry or the catalog: [text], with [arg] as its %1$s when it takes one. */
data class StoreMessage(
    @StringRes val text: Int,
    val arg: String? = null,
)

/**
 * The marketplace for one [kind]: catalogs published from GitHub repos to GitHub Pages
 * ([Store]s), the reviewed built-in one and any the user adds, widgets in index.json,
 * faces in faces.json and apps in apps.json. Installs are checked against the catalog's
 * SHA-256, land on the current page (a face left of page 1, an app in the drawer), and
 * remember their store and which version and commands were approved, so an update that
 * changes them asks again. A face with several pages installs them side by side;
 * each stays the user's to remove, and an update replaces only the ones still there.
 */
class Market(
    private val engine: WidgetEngine,
    val kind: OwlKind,
) {
    companion object {
        /** The marketplace repo: widgets are submitted there as pull requests. */
        const val REPO = "SimonSchubert/omarchy-widgets"

        /** Where its CI publishes index.json, faces.json, apps.json and the reviewed files. */
        const val BASE = "https://simonschubert.github.io/omarchy-widgets/"

        private const val VERSION = 1
        private const val STORES_VERSION = 1

        /** Decoded previews kept per store: a 1212x540 one takes 2.6 MB, so about a dozen. */
        private const val PREVIEW_BYTES = 32L shl 20

        /** Settings checks for updates with a catalog at most this old; the store always reloads it. */
        private const val STALE_MS = 60 * 60 * 1000L
    }

    /** What the user installed and approved, per [installKey]. */
    data class Installed(
        val id: String,
        val file: String,
        val version: String,
        val commands: List<String>,
        val requires: Map<String, List<String>>,
        /** The installed file's SHA-256, to tell it from one the user edited; null in records kept before it was. */
        val sha256: String? = null,
        /** A face's other pages, from page 2 on; none in records kept before faces had pages. */
        val pages: List<PageFile> = emptyList(),
    ) {
        /** Every page's file: [file] (page 1), then [pages]'. */
        val files get() = listOf(PageFile(file, sha256)) + pages
    }

    /** One installed page of a face, and its SHA-256 as installed. */
    data class PageFile(
        val file: String,
        val sha256: String?,
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
    var error by mutableStateOf<StoreMessage?>(null)
        private set
    val installed = mutableStateMapOf<String, Installed>()
    private val previews = StoreImages(PREVIEW_BYTES)
    private val icons = HashMap<Pair<String, Int>, ImageBitmap?>()

    /** When the catalog last loaded; settings reloads it only when it's older than [STALE_MS]. */
    var refreshedAt = 0L
        private set
    /** What's happening to an entry, by [installKey], while it is: a string resource. */
    private val busy = mutableStateMapOf<String, Int>()
    private val catalog = if (kind == OwlKind.Widget) "index.json" else "${kind.word}s.json"

    /** Each kind keeps its own records: an app and a widget may share an id. */
    private val record get() = File(engine.dir, if (kind == OwlKind.Widget) ".market.json" else ".market-${kind.word}s.json")
    private val storesRecord get() = File(engine.dir, ".stores.json")

    suspend fun refresh() {
        val store = current
        loading = true
        error = null
        try {
            val list = withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(store.base + catalog)), "${kind.word}s") }
            // The user may have picked another store meanwhile.
            if (current == store) {
                entries = list
                refreshedAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            if (current == store) {
                // A catalog the store doesn't publish yet (faces.json, before the first face) is an empty one.
                if (e.message?.startsWith("HTTP 404") == true) {
                    entries = emptyList()
                    refreshedAt = System.currentTimeMillis()
                } else {
                    error = StoreMessage(R.string.store_load_failed, e.message ?: e.toString())
                }
            }
        } finally {
            loading = false
        }
    }

    /** Reloads the catalog unless it loaded recently; a failed load is tried again next time. */
    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - refreshedAt >= STALE_MS) refresh()
    }

    /** Shows [store]'s widgets once refreshed. */
    fun select(store: Store) {
        current = store
        entries = emptyList()
        error = null
        refreshedAt = 0
        saveStores()
    }

    /** Adds and selects the store [input] names, once its catalog loads. Returns an error, or null. */
    suspend fun addStore(input: String): String? {
        val store = Store.parse(input) ?: return "use owner/repo or the store's URL"
        if (store in stores) return "${store.repo} is already in your stores"
        val list =
            try {
                withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(store.base + catalog)), "${kind.word}s") }
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
    ) = installed[installKey(store, e.id)]

    /** The record of an installed entry from the current store, if any. */
    private fun recordOf(e: CatalogEntry) = installedFrom(current, e)

    /** Installed from the current store, and one of its files is still there (removing the tile moves the file away). */
    fun installedVersion(e: CatalogEntry): Installed? = recordOf(e)?.takeIf { i -> removals >= 0 && i.files.any { File(engine.dir, it.file).exists() } }

    /** Page [n]'s file: a face's later pages add `.2`, … which no name can end with, so they never take another's file. */
    fun fileFor(
        e: CatalogEntry,
        n: Int = 1,
    ) = installFile(current, e.author, e.id.substringAfter('/'), kind).removeSuffix(".owl") + (if (n > 1) ".$n" else "") + ".owl"

    /** Bumped when [remove] moves files away, which no state shows, so the store's pages ask [installedVersion] again. */
    private var removals by mutableIntStateOf(0)

    /** Each of [e]'s pages' files, from page 1: the installed one, or where a new one goes. */
    private fun pageFiles(e: CatalogEntry): List<String> {
        val i = recordOf(e)
        return (1..1 + e.pages.size).map { n -> i?.files?.getOrNull(n - 1)?.file ?: fileFor(e, n) }
    }

    /**
     * How many of an installed face's pages aren't on home: removed there, or added by the version installed. Only "add
     * pages" brings them, never an update; 0 for one that isn't installed.
     */
    fun missingPages(e: CatalogEntry): Int = if (installedVersion(e) == null) 0 else pageFiles(e).count { !File(engine.dir, it).exists() }

    fun hasUpdate(e: CatalogEntry) = e.supported && installedVersion(e)?.let { Catalog.compareVersions(e.version, it.version) > 0 } == true

    /** Installed ones with an update, from the last loaded catalog. */
    val updates get() = entries.filter { hasUpdate(it) }

    /** Whether each installed file was edited, by its name, for the size and time it was checked at. */
    private val editedFiles = HashMap<String, Pair<Pair<Long, Long>, Boolean>>()

    /**
     * Changed since it was installed, by the user or their agent, so an update would throw their changes away. One
     * installed before installs were recorded with their hash may have been: it counts as edited.
     */
    fun edited(e: CatalogEntry): Boolean {
        val i = installedVersion(e) ?: return false
        // A face is edited when any page still there is; one removed on home has nothing to lose.
        return i.files.any { page -> File(engine.dir, page.file).exists() && edited(page) }
    }

    private fun edited(page: PageFile): Boolean {
        val sha = page.sha256 ?: return true
        val file = File(engine.dir, page.file)
        val stamp = file.length() to file.lastModified()
        editedFiles[page.file]?.takeIf { it.first == stamp }?.let { return it.second }
        val edited = runCatching { sha256Hex(file.readBytes()) != sha }.getOrDefault(true)
        editedFiles[page.file] = stamp to edited
        return edited
    }

    /**
     * Takes [e] off home or out of the drawer, every page of a face: its files go to .removed/, as removing it there does.
     * Its record stays, as it does then, so adding it again shows what changed since the version approved.
     */
    fun remove(e: CatalogEntry) {
        val names = installedVersion(e)?.files?.map { it.file }?.toSet() ?: return
        for (spec in engine.specs.filter { it.file.name in names }) engine.removeWidget(spec)
        removals++
    }

    /** An update runs different commands or needs other packages than what the user approved. */
    fun changedSinceApproval(e: CatalogEntry) = installedVersion(e)?.let { it.commands != e.commands || it.requires != e.requires } == true

    /** What's happening to [e] from the current store: a string resource, or null. */
    fun busy(e: CatalogEntry): Int? = busy[installKey(current, e.id)]

    /**
     * Downloads, verifies and installs [e] on [page], or updates it: an update replaces only the pages still on home. Its
     * packages install right after. Returns an error, or null.
     */
    suspend fun install(
        e: CatalogEntry,
        page: Int,
    ): StoreMessage? {
        val update = recordOf(e) != null
        return put(e, page, if (update) R.string.store_updating else R.string.store_installing) { exists -> !update || exists }
    }

    /** Adds an installed face's [missingPages] at the installed version, leaving the pages still on home as they are. */
    suspend fun addPages(e: CatalogEntry): StoreMessage? = put(e, 0, R.string.store_adding_pages) { exists -> !exists }

    /** Downloads every page [which] picks (by whether its file exists), checks them all, then writes them. */
    private suspend fun put(
        e: CatalogEntry,
        page: Int,
        @StringRes status: Int,
        which: (Boolean) -> Boolean,
    ): StoreMessage? {
        val store = current
        val key = installKey(store, e.id)
        busy[key] = status
        try {
            val names = pageFiles(e)
            val exists = withContext(Dispatchers.IO) { names.map { File(engine.dir, it).exists() } }
            val paths = listOf(e.path to e.sha256) + e.pages.map { it.path to it.sha256 }
            val picked = names.indices.filter { which(exists[it]) }
            // All of them or none: a face whose second page failed to download would be half there.
            val bytes =
                withContext(Dispatchers.IO) {
                    picked.associateWith { n -> httpGet(store.base + paths[n].first) }
                }
            if (bytes.any { (n, b) -> sha256Hex(b) != paths[n].second }) return StoreMessage(R.string.store_download_mismatch)
            // A page on home keeps its place; a new one goes where the user is. Apps go in the drawer, on no page, and a face's
            // pages left of page 1, side by side from the empty page waiting there leftwards, page 1 leftmost. A face's pages
            // added back take the ones still there along, so they stay in order. Their pages are all picked first: home
            // doesn't know one is taken until its file loads.
            val fresh = picked.filter { !exists[it] }
            if (kind == OwlKind.Widget) for (n in fresh) engine.setPage(names[n], page)
            if (kind == OwlKind.Face && fresh.isNotEmpty()) {
                val placed = names.indices.filter { exists[it] || it in picked }
                val left = engine.newFacePage() - placed.size + 1
                for ((k, n) in placed.withIndex()) engine.placeOnPage(names[n], left + k)
            }
            val written = ArrayList<Int>()
            try {
                withContext(Dispatchers.IO) {
                    for (n in picked) {
                        engine.installDependenciesWhenLoaded(names[n])
                        File(engine.dir, names[n]).writeBytes(bytes.getValue(n))
                        written += n
                    }
                }
            } finally {
                // What got written is recorded even when the rest failed, so it's still the store's to update.
                if (written.isNotEmpty()) record(store, e, names, written)
            }
            return null
        } catch (x: Exception) {
            return StoreMessage(R.string.store_install_failed, x.message ?: x.toString())
        } finally {
            busy.remove(key)
        }
    }

    /**
     * Records [e] as installed after the pages [written] were: each of those with the catalog's hash, the others as they
     * were recorded (a page kept on home may be edited), or with the catalog's when they never were. The version and approval are [e]'s:
     * an update and an add both leave every page there at it.
     */
    private fun record(
        store: Store,
        e: CatalogEntry,
        names: List<String>,
        written: List<Int>,
    ) {
        val key = installKey(store, e.id)
        val old = installed[key]?.files
        val hashes = listOf(e.sha256) + e.pages.map { it.sha256 }
        val files =
            names.mapIndexed { n, name ->
                // A kept page's null hash stays null: installed before hashes were kept, it may be edited.
                val kept = old?.getOrNull(n)?.takeIf { it.file == name }
                PageFile(name, if (n in written || kept == null) hashes[n] else kept.sha256)
            }
        installed[key] = Installed(key, files[0].file, e.version, e.commands, e.requires, files[0].sha256, files.drop(1))
        save()
    }

    /** [e]'s preview drawn in [theme] when the store has one, else its default preview. */
    suspend fun preview(
        e: CatalogEntry,
        theme: String?,
    ): ImageBitmap? = e.previewFor(theme)?.let { image(it) }

    /** A preview or screen from the current store, by its path in the catalog; null when it can't be loaded. */
    suspend fun image(path: String): ImageBitmap? {
        val url = current.base + path
        previews.decoded(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            bytes(url)
                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
                ?.also { previews.decoded(url, it) }
        }
    }

    /**
     * [e]'s preview for a grid card, no bigger than the card draws it: at most [width] px wide and [height] px high, and
     * for a whole screen (an app or a face) only its top, [top] of its width high. The full image is a phone screen of up
     * to 10 MB decoded; a dozen of those filled the cache, so scrolling decoded and uploaded them again and again.
     */
    suspend fun thumbnail(
        e: CatalogEntry,
        theme: String?,
        width: Int,
        height: Int,
        top: Float?,
    ): ImageBitmap? {
        val path = e.previewFor(theme) ?: return null
        val url = current.base + path
        val key = "$url#$width:$height:$top"
        previews.decoded(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            bytes(url)
                ?.let { runCatching { decodeThumbnail(it, width, height, top)?.asImageBitmap() }.getOrNull() }
                ?.also { previews.decoded(key, it) }
        }
    }

    /** The fetched file at the full [url], fetched once; null when it can't be. */
    private fun bytes(url: String): ByteArray? =
        if (previews.wasFetched(url)) {
            previews.fetched(url)
        } else {
            runCatching { httpGet(url) }.getOrNull().also { previews.fetched(url, it) }
        }

    /**
     * [e]'s icon at [size] px square, as the drawer draws it: an SVG with `currentColor` in [accent], an image as it is;
     * null when it has none.
     */
    suspend fun icon(
        e: CatalogEntry,
        size: Int,
        accent: Int,
    ): ImageBitmap? {
        val remote = e.iconFile?.let { current.base + it }
        val key = (e.icon ?: remote ?: return null) to (if (e.icon != null) accent else 0)
        synchronized(icons) { if (key in icons) return icons[key] }
        val img =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (e.icon != null) decodeSvgIcon(e.icon!!, size, accent) else decodeImageIcon(httpGet(remote!!), size)
                }.getOrNull()?.asImageBitmap()
            }
        synchronized(icons) { icons[key] = img }
        return img
    }

    private var loaded = false

    /** Reads the stores and install records; call off the main thread before the store opens. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        (Versioned.read(storesRecord.takeIf { it.exists() }?.readText(), STORES_VERSION) as? JSONObject)?.let { data ->
            stores = listOf(Store.BUILT_IN) + data.optJSONArray("stores")?.strings().orEmpty().mapNotNull { Store.parse(it) }.filterNot { it.reviewed }.distinct()
            current = Store.parse(data.optString("current"))?.takeIf { it in stores } ?: Store.BUILT_IN
        }
        val data = Versioned.readFile(record, VERSION) ?: return
        installed.putAll(
            data.mapEntries { id, f ->
                Installed(
                    id = id,
                    file = f.getString("file"),
                    version = f.getString("version"),
                    commands = f.optJSONArray("commands")?.strings().orEmpty(),
                    requires = f.optJSONObject("requires")?.let { r -> r.keys().asSequence().associateWith { r.optJSONArray(it)?.strings().orEmpty() } }.orEmpty(),
                    sha256 = f.optString("sha256").ifEmpty { null },
                    pages =
                        f.optJSONArray("pages")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p -> PageFile(p.getString("file"), p.optString("sha256").ifEmpty { null }) }
                        }.orEmpty(),
                )
            },
        )
    }

    /** The stores the user added, and the one picked last. */
    private fun saveStores() {
        val data = JSONObject().put("stores", JSONArray(stores.filterNot { it.reviewed }.map { it.repo })).put("current", current.repo)
        runCatching { writeAtomically(storesRecord, Versioned.write(STORES_VERSION, data).toByteArray()) }
    }

    private fun save() {
        val data = JSONObject()
        for (i in installed.values) {
            data.put(
                i.id,
                JSONObject()
                    .put("file", i.file)
                    .put("version", i.version)
                    .put("commands", JSONArray(i.commands))
                    .put("requires", JSONObject(i.requires.mapValues { JSONArray(it.value) } as Map<*, *>))
                    .apply { i.sha256?.let { put("sha256", it) } }
                    .apply {
                        if (i.pages.isNotEmpty()) {
                            put("pages", JSONArray(i.pages.map { p -> JSONObject().put("file", p.file).apply { p.sha256?.let { put("sha256", it) } } }))
                        }
                    },
            )
        }
        runCatching { Versioned.writeFile(record, VERSION, data) }
    }
}

/** See [Market.thumbnail]: decodes only the part that shows, subsampled, then scales it to fit; never up. */
private fun decodeThumbnail(
    bytes: ByteArray,
    width: Int,
    height: Int,
    top: Float?,
): Bitmap? {
    // The form without isShareable is API 31+; this one works from 29 (it's ignored either way).
    @Suppress("DEPRECATION")
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    try {
        val w = decoder.width
        val h = top?.let { minOf(decoder.height, (w * it).toInt().coerceAtLeast(1)) } ?: decoder.height
        val scale = minOf(1f, width.toFloat() / w, if (top == null) height.toFloat() / h else 1f)
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (w / (opts.inSampleSize * 2) >= w * scale) opts.inSampleSize *= 2
        val region = decoder.decodeRegion(Rect(0, 0, w, h), opts) ?: return null
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        if (region.width == tw && region.height == th) return region
        return Bitmap.createScaledBitmap(region, tw, th, true).also { if (it !== region) region.recycle() }
    } finally {
        decoder.recycle()
    }
}

/**
 * The store's previews and screens: as fetched (tens of KB each) for as long as the store is around, decoded (megabytes
 * each) only the most recently drawn, up to [maxBytes]. Keeping every decoded one took 460 MB once the widgets tab was
 * scrolled through.
 */
private class StoreImages(
    private val maxBytes: Long,
) {
    /** By path; null when it couldn't be fetched, which isn't tried again. */
    private val fetched = HashMap<String, ByteArray?>()

    /** The least recently drawn first. */
    private val decoded = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var decodedBytes = 0L

    private val ImageBitmap.bytes get() = width.toLong() * height * 4

    @Synchronized fun wasFetched(path: String) = path in fetched

    @Synchronized fun fetched(path: String) = fetched[path]

    @Synchronized fun fetched(
        path: String,
        bytes: ByteArray?,
    ) {
        fetched[path] = bytes
    }

    @Synchronized fun decoded(path: String) = decoded[path]

    @Synchronized fun decoded(
        path: String,
        image: ImageBitmap,
    ) {
        decoded.put(path, image)?.let { decodedBytes -= it.bytes }
        decodedBytes += image.bytes
        val eldest = decoded.values.iterator()
        // The one just drawn stays, however big.
        while (decodedBytes > maxBytes && decoded.size > 1) {
            decodedBytes -= eldest.next().bytes
            eldest.remove()
        }
    }
}
