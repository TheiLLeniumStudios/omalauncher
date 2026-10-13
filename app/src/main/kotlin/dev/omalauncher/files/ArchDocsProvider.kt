package dev.omalauncher.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * The Arch system's files under any Android app's fingers: a SAF (Storage
 * Access Framework) provider, so file managers, editors and Open/Save dialogs
 * see the guest's home (/root of the proot'd Arch rootfs) as a browsable tree,
 * with reads, writes in place, creation, deletion and renaming.
 *
 * Everything is plain host-side file operations: no Linux needs to be running,
 * and changes are visible to the guest on its next read (the launcher's own
 * watchers pick file changes up on their own).
 */
class ArchDocsProvider : DocumentsProvider() {

    private lateinit var docs: ArchDocs

    /** Where the shown tree roots; overridable so a build variant could retarget it. */
    protected open fun rootDir(context: Context): File = File(context.filesDir, "arch/root")

    override fun onCreate(): Boolean {
        val context = requireNotNull(getContext())
        docs = ArchDocs(rootDir(context))
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: rootProjection)
        cursor.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, docs.rootDocId)
            .add(DocumentsContract.Root.COLUMN_TITLE, "Omalauncher Linux")
            .add(DocumentsContract.Root.COLUMN_SUMMARY, "Arch files (/root)")
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, docs.rootDocId)
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            .add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, docs.root.usableSpace)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: documentProjection)
        include(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: documentProjection)
        for (id in docs.childIds(parentDocumentId)) include(cursor, id)
        return cursor
    }

    override fun getDocumentType(documentId: String) = mimeType(docs.fileFor(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String) = docs.isChild(parentDocumentId, documentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = docs.fileFor(documentId)
        // A write into a not-yet-existing document ("save as" from an editor) still gets its parent directory.
        if ('w' in mode && !file.exists()) file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = docs.fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("not a directory: $parentDocumentId")
        val asDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val target = docs.uniqueChild(parent, displayName)
        if (asDirectory) {
            if (!target.mkdirs()) throw FileNotFoundException("could not create ${target.path}")
        } else {
            if (!target.createNewFile()) throw FileNotFoundException("could not create ${target.path}")
        }
        notify(parentDocumentId)
        return docs.childDocId(parentDocumentId, target.name)
    }

    override fun deleteDocument(documentId: String) {
        docs.delete(documentId)
        notify(docs.parentOf(documentId))
    }

    /** The new document id, which clients go on using for the renamed file. */
    override fun renameDocument(documentId: String, displayName: String): String? =
        docs.rename(documentId, displayName).also { notify(docs.parentOf(documentId)) }

    private fun include(cursor: MatrixCursor, documentId: String): Boolean {
        val file =
            try {
                docs.fileFor(documentId)
            } catch (_: FileNotFoundException) {
                return false
            }
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, docs.displayNameFor(documentId))
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
            .add(
                DocumentsContract.Document.COLUMN_FLAGS,
                when {
                    file.isDirectory -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    else ->
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                },
            )
            .add(DocumentsContract.Document.COLUMN_SIZE, file.takeIf { it.isFile }?.length() ?: 0L)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        return true
    }

    /** SAF clients learn about changes by us notifying them; both the parent's listing and the row itself. */
    private fun notify(parentDocumentId: String) {
        val context = requireNotNull(context)
        val authority = authority(context)
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null)
        context.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, parentDocumentId), null)
    }

    private fun mimeType(file: File): String =
        when {
            file.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
            else ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
        }

    companion object {
        /** Whether other apps can see the Linux home; off as shipped (see the manifest). */
        fun isEnabled(context: Context) =
            context.packageManager.getComponentEnabledSetting(ComponentName(context, ArchDocsProvider::class.java)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        /**
         * Turns access on, or off for good: disabled (not back to the manifest's default) so Android drops connected
         * clients, and every grant apps were given is revoked, so turning it on again doesn't bring them back.
         */
        fun setEnabled(
            context: Context,
            on: Boolean,
        ) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, ArchDocsProvider::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (!on) {
                context.revokeUriPermission(
                    Uri.parse("content://${authority(context)}/"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        /** Per application id, as in the manifest, so the Play build and the fork can be installed side by side. */
        private fun authority(context: Context) = context.packageName + ".arch.documents"

        // The SDK hides its own projection arrays; these are what the methods use.
        private val rootProjection =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            )
        private val documentProjection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
