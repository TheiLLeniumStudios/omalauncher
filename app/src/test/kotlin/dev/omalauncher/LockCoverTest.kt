package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class LockCoverTest {
    private val evening = LocalDateTime.of(2026, 10, 13, 19, 5)

    @Test fun clockFollowsThe24HourSetting() {
        assertEquals("19:05", lockClock(evening, is24Hour = true, Locale.US).time)
        assertEquals("7:05", lockClock(evening, is24Hour = false, Locale.US).time)
        assertEquals("07:05", lockClock(evening.withHour(7), is24Hour = true, Locale.US).time)
    }

    @Test fun dateSpellsOutTheDay() {
        assertEquals("Tuesday, October 13", lockClock(evening, is24Hour = true, Locale.US).date)
    }
}
