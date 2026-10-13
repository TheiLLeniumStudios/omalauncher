package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoresTest {
    private val mine = Store("TheiLLeniumStudios/omarchy-widgets")

    @Test fun parsesOwnerSlashName() {
        assertEquals("TheiLLeniumStudios/omarchy-widgets", Store.parse("  TheiLLeniumStudios/omarchy-widgets ")?.repo)
        assertEquals("a-b/c.d_e", Store.parse("a-b/c.d_e")?.repo)
    }

    @Test fun parsesGithubUrls() {
        for (url in listOf(
            "https://github.com/TheiLLeniumStudios/omarchy-widgets",
            "https://github.com/TheiLLeniumStudios/omarchy-widgets/",
            "https://github.com/TheiLLeniumStudios/omarchy-widgets.git",
        )) {
            assertEquals(url, "TheiLLeniumStudios/omarchy-widgets", Store.parse(url)?.repo)
        }
    }

    @Test fun parsesPagesUrls() {
        assertEquals(mine, Store.parse("https://theilleniumstudios.github.io/omarchy-widgets/"))
        assertEquals(mine, Store.parse("https://theilleniumstudios.github.io/omarchy-widgets"))
    }

    @Test fun rejectsJunk() {
        for (junk in listOf("", "  ", "owner", "https://example.com/x/y", "owner/name/extra", "own er/name", "owner/na me", "https://github.com/owner")) {
            assertNull(junk, Store.parse(junk))
        }
    }

    @Test fun baseLowercasesTheOwner() {
        assertEquals("https://theilleniumstudios.github.io/omarchy-widgets/", mine.base)
        assertEquals("https://simonschubert.github.io/omarchy-widgets/", Store.BUILT_IN.base)
    }

    @Test fun builtInIsTheSameStoreWhateverTheCase() {
        val typed = Store.parse("simonschubert/omarchy-widgets")
        assertEquals(Store.BUILT_IN, typed)
        assertEquals(Store.BUILT_IN.hashCode(), typed.hashCode())
        assertTrue(typed!!.reviewed)
        assertFalse(mine.reviewed)
        assertNotEquals(Store.BUILT_IN, mine)
    }

    @Test fun builtInKeepsItsInstallKeysAndFiles() {
        // What .market.json and the widgets dir already hold.
        assertEquals("simon/clock", installKey(Store.BUILT_IN, "simon/clock"))
        assertEquals("market-simon-clock.owl", installFile(Store.BUILT_IN, "simon", "clock"))
    }

    @Test fun otherStoresDontClash() {
        val other = Store("Some.One/My_Widgets")
        val keys = listOf(Store.BUILT_IN, mine, other).map { installKey(it, "simon/clock") }
        assertEquals(3, keys.toSet().size)
        assertEquals("TheiLLeniumStudios/omarchy-widgets:simon/clock", installKey(mine, "simon/clock"))
        assertEquals("market-theilleniumstudios-omarchy-widgets-simon-clock.owl", installFile(mine, "simon", "clock"))
        assertEquals("market-some-one-my-widgets-simon-clock.owl", installFile(other, "simon", "clock"))
    }
}
