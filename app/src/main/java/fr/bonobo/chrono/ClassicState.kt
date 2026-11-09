// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

/** Service-owned monotonic timing anchors. Views only project this state. */
data class ClassicState(
    val down: Boolean = true,
    val running: Boolean = false,
    val done: Boolean = false,
    val base: Long = 0,
    val startAt: Long = 0,
    val target: Long = 0
) {
    fun elapsed(now: Long): Long = base + if (running) (now - startAt).coerceAtLeast(0) else 0
}
