package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileKindTest {
    @Test fun android15ClassifiesAllKnownProfileKinds() {
        assertEquals(ProfileKind.Work, classifyProfile(35, "android.os.usertype.profile.MANAGED"))
        assertEquals(ProfileKind.Clone, classifyProfile(35, "android.os.usertype.profile.CLONE"))
        assertEquals(ProfileKind.Private, classifyProfile(35, "android.os.usertype.profile.PRIVATE"))
    }

    /** Only Private Space is hidden: an OEM's own profile type, or one Android won't describe, keeps its apps like a work profile. */
    @Test fun unknownModernProfilesCountAsWork() {
        assertEquals(ProfileKind.Work, classifyProfile(35, null))
        assertEquals(ProfileKind.Work, classifyProfile(37, "com.oem.usertype.profile.DUAL"))
    }

    /** A Private Space Android stops describing (locked, or a failed call) stays private rather than showing up as work. */
    @Test fun aKnownPrivateProfileStaysPrivateWithoutInfo() {
        assertEquals(ProfileKind.Private, classifyProfile(35, null, knownPrivate = true))
    }

    @Test fun olderAndroidKeepsLegacyWorkProfileBehavior() {
        assertEquals(ProfileKind.Work, classifyProfile(34, null))
    }
}
