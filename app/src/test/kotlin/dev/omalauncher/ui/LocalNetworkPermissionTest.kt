package dev.omalauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test fun android17WithoutTheGrantAsks() {
        assertTrue(needsLocalNetworkPermission(sdkInt = 37, granted = false))
    }

    @Test fun android17WithTheGrantDoesntAsk() {
        assertFalse(needsLocalNetworkPermission(sdkInt = 37, granted = true))
    }

    @Test fun olderAndroidNeverAsks() {
        for (sdkInt in 29..36) {
            assertFalse("API $sdkInt without a grant", needsLocalNetworkPermission(sdkInt, granted = false))
            assertFalse("API $sdkInt with a grant", needsLocalNetworkPermission(sdkInt, granted = true))
        }
    }

    @Test fun newerAndroidOnlyAsksWithoutTheGrant() {
        assertTrue(needsLocalNetworkPermission(sdkInt = 38, granted = false))
        assertFalse(needsLocalNetworkPermission(sdkInt = 38, granted = true))
    }

    /** Linux installed before this asked at install time: home asks once, then a refusal stands. */
    @Test fun homeAsksInstalledLinuxOnce() {
        assertTrue(asksLocalNetworkAtHome(sdkInt = 37, granted = false, linuxInstalled = true, askedBefore = false))
        assertFalse(asksLocalNetworkAtHome(sdkInt = 37, granted = false, linuxInstalled = true, askedBefore = true))
        assertFalse(asksLocalNetworkAtHome(sdkInt = 37, granted = false, linuxInstalled = false, askedBefore = false))
        assertFalse(asksLocalNetworkAtHome(sdkInt = 37, granted = true, linuxInstalled = true, askedBefore = false))
        assertFalse(asksLocalNetworkAtHome(sdkInt = 36, granted = false, linuxInstalled = true, askedBefore = false))
    }
}
