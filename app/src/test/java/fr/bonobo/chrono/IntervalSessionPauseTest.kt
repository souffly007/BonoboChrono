// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalSessionPauseTest {
    @Test fun resumeKeepsAbsoluteElapsedTime() {
        val original = IntervalSession(10_000, 5_000, 2, 1_000)
        val pausedAt = 7_000L
        val elapsed = pausedAt - original.startedAt
        val resumed = original.copy(startedAt = 100_000, elapsedBeforeStart = elapsed)
        assertEquals(4_000L, resumed.position(100_000).remaining)
        assertEquals(2, resumed.position(104_000).phase)
        assertEquals(5_000L, resumed.position(104_000).remaining)
    }
}
