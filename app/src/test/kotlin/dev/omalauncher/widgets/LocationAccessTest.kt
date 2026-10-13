package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WithoutLocation
import dev.omalauncher.widgets.runtime.mayLocate
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** `android.location` is for the launcher's own widgets and the user's; store and shared ones read null. */
class LocationAccessTest {
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

    @Test fun storeAndSharedWidgetsMayNotLocate() {
        assertFalse(mayLocate("market-simon-weather.owl", bundled = false, own = true))
        assertFalse(mayLocate("market-me-repo-simon-weather.owl", bundled = false, own = false))
        assertFalse(mayLocate("shared-weather.owl", bundled = false, own = false))
        assertTrue(mayLocate("10-today.owl", bundled = true, own = false))
        assertTrue(mayLocate("my-weather.owl", bundled = false, own = true))
    }

    /** As without the permission, and without asking the launcher, which would raise the permission dialog. */
    @Test fun aStoreWidgetReadsNullLocation() {
        val gated = WithoutLocation(located)
        assertEquals(Null, gated.android("location"))
        assertNull(gated.androidUpdates("location"))
        assertTrue(asked.isEmpty())
        assertEquals(Num(1.0), gated.android("battery"))
    }
}
