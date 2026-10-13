package dev.omalauncher.files

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchDocsTest {

    @get:Rule var tmp = TemporaryFolder()

    private fun docs(root: File = tmp.newFolder("root")) = ArchDocs(root)

    @Test fun roundsTripsIdsThroughWeirdNames() {
        val docs = docs()
        for (name in listOf("héllo wörld", "notes #1 (2026)", "a+b", "a%2Fb", "…", "emoji 🐛", "tab\tname", "%100%")) {
            val id = docs.childDocId(docs.rootDocId, name)
            assertEquals(name, docs.fileFor(id).name)
            assertEquals(name, docs.displayNameFor(id))
        }
    }

    /** Crafted ids must never decode their way out of the shown root. */
    @Test fun rejectsTraversalAttempts() {
        val docs = docs()
        for (attempt in listOf("../ssh", "a/../../etc", "%2E%2E", "a/%2E%2E/%2E%2E/etc")) {
            assertThrows(FileNotFoundException::class.java) { docs.fileFor(attempt) }
        }
    }

    @Test fun listingSkipsBrokenSymlinks() {
        val root = tmp.newFolder("root")
        File(root, "real").createNewFile()
        File(root, "dir").mkdir()
        Files.createSymbolicLink(File(root, "dangling").toPath(), Path.of(root.name, "missing"))
        val docs = ArchDocs(root)
        assertEquals(listOf("dir", "real"), docs.visibleChildren(root).map(File::getName))
    }

    /** A symlink pointing outside the shown root must resolve to nothing usable. */
    @Test fun refusesSymlinksThatEscapeTheRoot() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret").apply { createNewFile() }
        Files.createSymbolicLink(File(root, "escape").toPath(), secret.toPath())
        val docs = ArchDocs(root)
        assertThrows(FileNotFoundException::class.java) { docs.fileFor(docs.childDocId(docs.rootDocId, "escape")) }
    }

    /** An app granted one folder can't climb out of it with `..`, spelled out or percent-encoded. */
    @Test fun dotDotCannotEscapeAGrantedFolder() {
        val root = tmp.newFolder("root")
        File(root, "Documents").mkdir()
        File(root, ".ssh").mkdir()
        File(root, ".ssh/id_ed25519").writeText("secret")
        val docs = ArchDocs(root)
        val granted = docs.childDocId(docs.rootDocId, "Documents")
        for (id in listOf("$granted/../.ssh/id_ed25519", "$granted/%2E%2E/.ssh/id_ed25519", "$granted/./../.ssh", "$granted//x", "$granted/a%2Fb")) {
            assertThrows(id, FileNotFoundException::class.java) { docs.fileFor(id) }
            assertFalse(id, docs.isChild(granted, id))
        }
    }

    /** Picking the whole home as a tree must give access to what it lists. */
    @Test fun everyListedChildIsInItsParentsTree() {
        val root = tmp.newFolder("root")
        File(root, "a/b").mkdirs()
        File(root, "a/b/c.txt").createNewFile()
        File(root, "root").createNewFile()
        val docs = ArchDocs(root)
        val top = docs.childIds(docs.rootDocId)
        assertEquals(2, top.size)
        for (id in top) assertTrue(id, docs.isChild(docs.rootDocId, id))
        val a = top.first { docs.displayNameFor(it) == "a" }
        for (id in docs.childIds(docs.childIds(a).single())) {
            assertTrue(id, docs.isChild(docs.rootDocId, id))
            assertTrue(id, docs.isChild(a, id))
        }
        // A file called "root" is not the root document.
        assertTrue(docs.fileFor(top.first { docs.displayNameFor(it) == "root" }).isFile)
    }

    @Test fun parentOfARootChildIsTheRoot() {
        val docs = docs()
        val a = docs.childDocId(docs.rootDocId, "a")
        assertEquals(docs.rootDocId, docs.parentOf(a))
        assertEquals(a, docs.parentOf(docs.childDocId(a, "b")))
    }

    /** Deleting a folder removes links in it, never what they point at. */
    @Test fun deleteDoesNotFollowSymlinks() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        val kept = File(outside, "kept").apply { writeText("x") }
        File(root, "dir").mkdir()
        Files.createSymbolicLink(File(root, "dir/link").toPath(), outside.toPath())
        val docs = ArchDocs(root)
        docs.delete(docs.childDocId(docs.rootDocId, "dir"))
        assertFalse(File(root, "dir").exists())
        assertTrue(kept.exists())
    }

    /** Linux needs its home: the root itself can't be deleted or renamed. */
    @Test fun theRootCannotBeDeletedOrRenamed() {
        val docs = docs()
        assertThrows(UnsupportedOperationException::class.java) { docs.delete(docs.rootDocId) }
        assertThrows(UnsupportedOperationException::class.java) { docs.rename(docs.rootDocId, "elsewhere") }
        assertTrue(docs.root.isDirectory)
    }

    @Test fun renameKeepsTheFileInItsFolder() {
        val root = tmp.newFolder("root")
        File(root, "a").mkdir()
        File(root, "a/x").createNewFile()
        val docs = ArchDocs(root)
        val a = docs.childDocId(docs.rootDocId, "a")
        val renamed = docs.rename(docs.childDocId(a, "x"), "y")
        assertEquals(docs.childDocId(a, "y"), renamed)
        assertTrue(File(root, "a/y").isFile)
    }

    /** A link that stays inside the root still works and keeps its own identity. */
    @Test fun resolvesSymlinksInsideTheRoot() {
        val root = tmp.newFolder("root")
        File(root, "target").createNewFile()
        Files.createSymbolicLink(File(root, "link").toPath(), Path.of("target"))
        val docs = ArchDocs(root)
        val id = docs.childDocId(docs.rootDocId, "link")
        assertTrue(docs.fileFor(id).isFile)
    }

    @Test fun uniquifiesCreatedNamesWithoutOverwriting() {
        val root = tmp.newFolder("root")
        val docs = ArchDocs(root)
        assertEquals("notes", docs.uniqueChild(root, "notes").name)
        File(root, "notes").createNewFile()
        assertEquals("notes (2)", docs.uniqueChild(root, "notes").name)
        File(root, "notes.txt").createNewFile()
        assertEquals("notes (2).txt", docs.uniqueChild(root, "notes.txt").name)
    }

    @Test fun refusesUnusableNames() {
        val docs = docs()
        for (bad in listOf("", ".", "..", "a/b", "a/b/")) {
            assertThrows(FileNotFoundException::class.java) { docs.uniqueChild(tmp.newFolder(), bad) }
        }
    }

    @Test fun idsRoundTripTheRootItself() {
        val docs = docs()
        assertEquals(docs.root, docs.fileFor("root"))
        assertEquals("/", docs.displayNameFor("root"))
    }

    /** A fixed authority would stop this build installing beside another one (Play's and the fork's). */
    @Test fun everyProviderAuthorityFollowsTheApplicationId() {
        val authorities = Regex("""android:authorities="([^"]*)"""").findAll(File("src/main/AndroidManifest.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(authorities.isNotEmpty())
        for (authority in authorities) assertTrue(authority, authority.startsWith("\${applicationId}."))
    }

    /** The home holds keys and agent logins: other apps only see it once the user turns it on in Linux settings. */
    @Test fun theProviderShipsTurnedOff() {
        val provider = Regex("""<provider\s[^>]*ArchDocsProvider[^>]*>""").find(File("src/main/AndroidManifest.xml").readText())!!.value
        assertTrue(provider, "android:enabled=\"false\"" in provider)
    }
}
