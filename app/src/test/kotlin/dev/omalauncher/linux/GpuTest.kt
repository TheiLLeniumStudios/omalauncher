package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTest {
    @Test fun noNodeMeansNoDriver() {
        assertEquals(null, gpuKind(hasMali = false, hasKgsl = false))
    }

    @Test fun maliNodeSelectsPanvk() {
        assertEquals(GpuKind.MALI, gpuKind(hasMali = true, hasKgsl = false))
    }

    @Test fun kgslNodeSelectsTurnip() {
        assertEquals(GpuKind.TURNIP, gpuKind(hasMali = false, hasKgsl = true))
    }

    /** A phone has one GPU. If both nodes exist, keep the Mali driver this phone already used. */
    @Test fun bothNodesStayOnMali() {
        assertEquals(GpuKind.MALI, gpuKind(hasMali = true, hasKgsl = true))
    }

    @Test fun turnipSkipsAdrenoOlderThan6xx() {
        assertFalse(turnipSupports("Adreno540v2"))
        assertFalse(turnipSupports("Adreno (TM) 512"))
    }

    @Test fun turnipTakesAdreno6xxAndNewer() {
        assertTrue(turnipSupports("Adreno618v1"))
        assertTrue(turnipSupports("Adreno642Lv1"))
        assertTrue(turnipSupports("Adreno722v1\n"))
        assertTrue(turnipSupports("Adreno830v2"))
    }

    /** An unreadable or unknown model doesn't hide the option: the probe decides. */
    @Test fun unknownAdrenoModelLeavesItToTheProbe() {
        assertTrue(turnipSupports(null))
        assertTrue(turnipSupports(""))
    }

    /**
     * The Vulkan loader's default ICD dirs on Arch. An ICD there loads even with the GPU off,
     * and the /usr/share one belongs to pacman packages.
     */
    @Test fun icdsLoadOnlyThroughVkIcdFilenames() {
        val searched = listOf("/etc/xdg/vulkan/icd.d/", "/etc/vulkan/icd.d/", "/usr/local/etc/vulkan/icd.d/", "/usr/local/share/vulkan/icd.d/", "/usr/share/vulkan/icd.d/")
        for (kind in GpuKind.entries) {
            val driver = GpuDrivers.of(kind)
            assertTrue(searched.none { driver.icdPath.startsWith(it) })
            assertFalse(GpuDrivers.LEGACY_ICDS.contains(driver.icdPath))
            val probe = GpuDrivers.probeScript(driver)
            assertTrue(probe.indexOf("> ${driver.icdPath}") in 0 until probe.indexOf("vulkaninfo"))
            assertTrue(probe.contains("export VK_ICD_FILENAMES=${driver.icdPath};"))
        }
    }

    @Test fun maliScriptStaysThePinnedPanvkBlob() {
        val driver = GpuDrivers.of(GpuKind.MALI)
        val script = GpuDrivers.installScript(driver)
        assertTrue(script.contains("libvulkan_panfrost-glibc-aarch64.so"))
        assertTrue(script.contains("0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820  /usr/local/lib64/libvulkan_panfrost.so"))
        assertTrue(script.contains("0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820  /tmp/panvk.so"))
        assertTrue(GpuDrivers.probeScript(driver).contains("\"api_version\":\"1.4.363\""))
        assertFalse(script.contains("turnip"))
        assertFalse(script.contains("spirv-tools"))
        val probe = GpuDrivers.probeScript(driver)
        assertTrue(probe.contains("echo \"\$vk\""))
        assertTrue(probe.contains("grep -q DRIVER_ID_MESA_PANVK"))
        assertTrue(probe.contains("Vulkan: the Mali driver did not load"))
        assertTrue(probe.contains("export MESA_VK_WSI_DEBUG=sw;"))
        assertTrue(probe.contains("export GALLIUM_DRIVER=zink;"))
        assertTrue(probe.contains("unset LIBGL_ALWAYS_SOFTWARE"))
    }

    @Test fun turnipScriptPinsTheKgslPackageAndTheExtractedLibrary() {
        val driver = GpuDrivers.of(GpuKind.TURNIP)
        val script = GpuDrivers.installScript(driver)
        assertTrue(script.contains("turnip-26.3.0-devel-20260824/vulkan-freedreno-1-26.3.0-1-aarch64.pkg.tar.xz"))
        assertTrue(script.contains("9824514d7180402d936f83f4ad422f338b7df31ff2804eab8a0badb13ee44a77  /tmp/turnip.pkg.tar.xz"))
        assertTrue(script.contains("d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f  /usr/local/lib64/libvulkan_freedreno.so"))
        assertTrue(script.contains("d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f  /tmp/turnip-driver/usr/lib/libvulkan_freedreno.so"))
        assertTrue(script.contains("tar -xJf /tmp/turnip.pkg.tar.xz -C /tmp/turnip-driver usr/lib/libvulkan_freedreno.so"))
        assertTrue(script.contains("'spirv-tools'"))
        assertTrue(script.contains("'xcb-util-keysyms'"))
        assertTrue(GpuDrivers.probeScript(driver).contains("\"api_version\":\"1.4.359\""))
        assertFalse(script.contains("panvk"))
        val probe = GpuDrivers.probeScript(driver)
        assertTrue(probe.contains("grep -q DRIVER_ID_MESA_TURNIP"))
        assertTrue(probe.contains("Vulkan: the Turnip driver did not load"))
        assertTrue(probe.contains("export MESA_VK_WSI_DEBUG=sw;"))
    }
}
