package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.market.Review
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WithoutLocation
import dev.omalauncher.widgets.runtime.mayLocate
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** `android.location` is only for a widget exactly as the launcher ships it; everything else reads null. */
class LocationAccessTest {
    private val today = File("src/main/assets/widgets/10-today.owl").readBytes()

    /** What the launcher ships, by file name: the SHA-256 of each bundled widget. */
    private val bundled = mapOf("10-today.owl" to setOf(Review.sha256(today)))

    @Test fun theBundledWeatherWidgetAsShippedMayLocate() {
        assertTrue(mayLocate("10-today.owl", Review.sha256(today), bundled))
    }

    /** A guest command can rewrite the file under the bundled name; then it's not the launcher's widget any more. */
    @Test fun anEditedBundledWidgetMayNot() {
        val edited = today + "\n// sends it elsewhere\n".toByteArray()
        assertFalse(mayLocate("10-today.owl", Review.sha256(edited), bundled))
    }

    /** Copied under another name, from a store, a shared link or by hand: never. */
    @Test fun anyOtherWidgetMayNot() {
        assertFalse(mayLocate("market-simon-weather.owl", Review.sha256(today), bundled))
        assertFalse(mayLocate("shared-weather.owl", Review.sha256(today), bundled))
        assertFalse(mayLocate("my-weather.owl", Review.sha256(today), bundled))
        assertFalse(mayLocate("10-today.owl", null, bundled))
    }

    /** Launcher services whose location is always known, recording what was asked for. */
    private val asked = ArrayList<String>()
    private val located =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, method, args ->
            when (method.name) {
                "android" -> Num(1.0).also { asked += args[0] as String }
                "androidUpdates" -> flowOf<Value>(Num(1.0)).also { asked += args[0] as String }
                else -> null
            }
        } as Services

    /** As without the permission, and without asking the launcher, which would raise the permission dialog. */
    @Test fun aWidgetThatMayNotLocateReadsNull() {
        val gated = WithoutLocation(located)
        assertEquals(Null, gated.android("location"))
        assertNull(gated.androidUpdates("location"))
        assertTrue(asked.isEmpty())
        assertEquals(Num(1.0), gated.android("battery"))
    }
}
