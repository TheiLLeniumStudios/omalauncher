package dev.omalauncher.theme

import dev.omalauncher.httpGet
import dev.omalauncher.upgrade.writeAtomically
import java.io.File

/** What we keep from the Omarchy repo: themes, config templates and app configs. */
private val KEEP_PATHS =
    listOf(
        "themes/", "default/themed/", "default/bash/", "config/btop/", "config/starship.toml",
        "config/lazygit/", "config/tmux/", "etc/fastfetch/", "icon.txt",
    )

/**
 * Omarchy's repo at [ThemeStore.OMARCHY_REF], unpacked where Omarchy keeps it
 * (~/.local/share/omarchy). A download replaces the whole directory in one
 * rename once complete: an interrupted one leaves the previous version, and
 * nothing lingers from it after a successful one.
 */
class OmarchyRelease(
    val dir: File,
) {
    private val marker = File(dir, ".omalauncher-ref")

    fun isCurrent() = runCatching { marker.readText().trim() }.getOrNull() == ThemeStore.OMARCHY_REF

    /**
     * Fetches one theme's colors and one of its backgrounds alone, from
     * raw.githubusercontent.com at the same commit: a few MB instead of the
     * release, so first run doesn't wait for it. [download] replaces them
     * with the full release later. Blocking.
     */
    fun downloadTheme(
        name: String,
        background: String?,
    ) {
        val theme = File(dir, "themes/$name")
        // The background first: a theme dir counts as installed once colors.toml is there.
        background?.let { fetch("themes/$name/backgrounds/$it", File(theme, "backgrounds/$it")) }
        fetch("themes/$name/colors.toml", File(theme, "colors.toml"))
    }

    private fun fetch(
        path: String,
        target: File,
    ) {
        val url = "https://raw.githubusercontent.com/omacom/omarchy/${ThemeStore.OMARCHY_REF}/$path"
        writeAtomically(target, httpGet(url, connectTimeout = 15_000, readTimeout = 30_000))
    }

    /**
     * The pinned commit, repacked by CI without the intro videos and lock-screen images
     * (scripts/pack-omarchy.sh): ~60 MB in one request, where GitHub's own tarball is
     * ~780 MB and fetching the kept files one by one is hundreds of rate-limited requests.
     * Blocking. [onProgress] gets the megabytes read so far.
     */
    fun download(onProgress: (Int) -> Unit) {
        val staging = File(dir.parentFile, ".omarchy.download").apply { deleteRecursively() }
        unpackTarball("$PACKS/omarchy-${ThemeStore.OMARCHY_REF}/omarchy-${ThemeStore.OMARCHY_REF}.tar.gz", staging, onProgress) { path ->
            KEEP_PATHS.any { path.startsWith(it) }
        }
        File(staging, marker.name).writeText(ThemeStore.OMARCHY_REF + "\n")
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "can't move the themes into place" }
    }
}

/** The fork's releases, where CI publishes each pinned commit's pack (apk.yml) before any APK that needs it. */
private const val PACKS = "https://github.com/TheiLLeniumStudios/omalauncher/releases/download"
