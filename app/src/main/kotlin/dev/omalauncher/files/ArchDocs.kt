package dev.omalauncher.files

import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Document ids and file mapping for the SAF provider, as plain java.io calls so
 * they can be tested on the JVM without an Android context.
 *
 * The shown root is the guest user's home (/root of the proot'd Arch system).
 * A document id is "root" for it, or "/" and a path whose segments are each
 * URL-encoded and joined with '/'. [fileFor] refuses empty, `.` and `..`
 * segments and decoded ones holding a '/', so an id can only name a file
 * by going down; what's in which tree is decided on canonical files ([isChild]).
 */
class ArchDocs(val root: File) {

    /** The root document's id. */
    val rootDocId = "root"

    private val rootCanonical = root.canonicalFile.path

    /** The ids of [parentDocId]'s children, as listing gives them out. */
    fun childIds(parentDocId: String): List<String> = visibleChildren(fileFor(parentDocId)).map { childDocId(parentDocId, it.name) }

    /** Whether [docId] is [parentDocId] or under it, by where the files really are (links resolved), not by how the ids read. */
    fun isChild(
        parentDocId: String,
        docId: String,
    ): Boolean {
        val parent = runCatching { fileFor(parentDocId).canonicalFile }.getOrNull() ?: return false
        val child = runCatching { fileFor(docId).canonicalFile }.getOrNull() ?: return false
        return child == parent || child.path.startsWith(parent.path + File.separator)
    }

    /** The folder [docId] is in; the root's children are in the root. */
    fun parentOf(docId: String): String = if (docId.lastIndexOf('/') > 0) docId.substringBeforeLast('/') else rootDocId

    /** Deletes [docId], a folder with all it holds; links in it are removed, never followed. */
    @OptIn(ExperimentalPathApi::class)
    fun delete(docId: String) {
        if (docId == rootDocId) throw UnsupportedOperationException("the Linux home can't be deleted")
        val path = fileFor(docId).toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw FileNotFoundException(docId)
        path.deleteRecursively()
    }

    /** Renames [docId] in its folder; returns its new id. */
    fun rename(
        docId: String,
        displayName: String,
    ): String {
        if (docId == rootDocId) throw UnsupportedOperationException("the Linux home can't be renamed")
        val file = fileFor(docId)
        val target = uniqueChild(file.parentFile ?: throw FileNotFoundException(docId), displayName)
        // renameTo fails instead of copying across devices; the parent is the same, so that can't happen here.
        if (!file.renameTo(target)) throw IllegalStateException("could not rename ${file.path} to $displayName")
        return childDocId(parentOf(docId), target.name)
    }

    /** The document id of a child of [parentDocId] named [name], as listing and creation give ids out. */
    fun childDocId(parentDocId: String, name: String): String =
        (if (parentDocId == rootDocId) "" else parentDocId) + "/" + URLEncoder.encode(name, Charsets.UTF_8)

    /**
     * The file a document id means. An id that isn't one this provider hands out
     * (empty, `.` or `..` segments, an encoded '/'), or that resolves outside
     * [root] through a symlink, is rejected, not served.
     */
    fun fileFor(docId: String): File {
        if (docId == rootDocId) return root
        if (!docId.startsWith("/")) throw FileNotFoundException("not a document id: $docId")
        val file =
            docId.substring(1).split('/').fold(root) { parent, segment ->
                val name = runCatching { URLDecoder.decode(segment, Charsets.UTF_8) }.getOrNull()
                if (name.isNullOrEmpty() || name == "." || name == ".." || '/' in name) throw FileNotFoundException("not a document id: $docId")
                File(parent, name)
            }
        val canonical = file.canonicalFile.path
        if (canonical != rootCanonical && !canonical.startsWith(rootCanonical + File.separator)) {
            throw FileNotFoundException("not under the Arch root: $docId")
        }
        return file
    }

    /** A file's display name, decoded from the last id segment; the root itself shows as the guest home. */
    fun displayNameFor(docId: String): String =
        if (docId == rootDocId) "/" else URLDecoder.decode(docId.substringAfterLast('/'), Charsets.UTF_8)

    /** Children of a directory as SAF should see them: broken symlinks are skipped, everything else is shown. */
    fun visibleChildren(dir: File): List<File> = dir.listFiles().orEmpty().filter { it.exists() }.sortedBy { it.path.lowercase() }

    /**
     * [displayName] as a usable name in [dir], uniquified on collision: SAF
     * creation must not silently overwrite, and appending (2) keeps the id and
     * display name consistent for both.
     */
    fun uniqueChild(dir: File, displayName: String): File {
        if (displayName.isEmpty() || displayName == "." || displayName == ".." || displayName.contains('/')) {
            throw FileNotFoundException("not a usable name: $displayName")
        }
        var file = File(dir, displayName)
        var i = 2
        while (file.exists()) {
            file = File(dir, uniquified(displayName, i))
            i++
        }
        return file
    }

    /** "notes" with the next free number appended: "notes (3)", keeping the extension ("notes (3).txt"). */
    private fun uniquified(name: String, i: Int): String {
        if (!name.contains('.')) return "$name ($i)"
        val base = name.substringBeforeLast('.')
        return if (base.isEmpty()) "$name ($i)" else "$base ($i)." + name.substringAfterLast('.')
    }
}
