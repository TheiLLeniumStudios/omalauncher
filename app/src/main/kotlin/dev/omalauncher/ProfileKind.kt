package dev.omalauncher

internal enum class ProfileKind { Personal, Work, Clone, Private }

/** Android's public user type values, and the kind each means. */
internal val KNOWN_USER_TYPES =
    mapOf(
        "android.os.usertype.profile.MANAGED" to ProfileKind.Work,
        "android.os.usertype.profile.CLONE" to ProfileKind.Clone,
        "android.os.usertype.profile.PRIVATE" to ProfileKind.Private,
    )

/**
 * A profile's kind from its [userType] (Android 15 on; before, every profile beside ours is work). Only Private Space
 * is hidden: an unknown type (an OEM's own) or a missing one counts as work, unless the profile is [knownPrivate].
 */
internal fun classifyProfile(
    sdk: Int,
    userType: String?,
    knownPrivate: Boolean = false,
): ProfileKind =
    when {
        sdk < 35 -> ProfileKind.Work
        userType == null && knownPrivate -> ProfileKind.Private
        else -> KNOWN_USER_TYPES[userType] ?: ProfileKind.Work
    }
