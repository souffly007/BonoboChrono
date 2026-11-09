// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

/** Bounds also protect deadlines, wake-lock timeouts and total session arithmetic. */
const val MAX_DURATION_MS = 365L * 24 * 60 * 60 * 1000

fun durationMs(hours: String, minutes: String, seconds: String): Long? {
    fun number(text: String): Long? =
        if (text.isBlank()) 0L else text.toLongOrNull()?.takeIf { it >= 0 }
    val h = number(hours) ?: return null
    val m = number(minutes) ?: return null
    val s = number(seconds) ?: return null
    val limit = MAX_DURATION_MS / 1000
    if (h > limit / 3600 || m > limit / 60 || s > limit) return null
    val sum = h * 3600 + m * 60 + s
    return if (sum <= limit) sum * 1000 else null
}

fun validSession(d1: Long, d2: Long, total: Int): Boolean =
    d1 in 1..MAX_DURATION_MS && d2 in 1..MAX_DURATION_MS && total > 0 &&
        d1 + d2 <= MAX_DURATION_MS / total

/** Immutable timing state. Pauses can later freeze elapsedMs without changing phase logic. */
data class IntervalSession(
    val d1: Long,
    val d2: Long,
    val total: Int,
    val startedAt: Long,
    val elapsedBeforeStart: Long = 0
) {
    init {
        require(validSession(d1, d2, total))
        require(elapsedBeforeStart in 0..((d1 + d2) * total))
    }

    data class Position(val phase: Int, val cycle: Int, val remaining: Long, val finished: Boolean)

    fun position(now: Long): Position {
        val cycleDuration = d1 + d2
        val elapsed = elapsedBeforeStart + (now - startedAt)
            .coerceIn(0, cycleDuration * total - elapsedBeforeStart)
        if (elapsed >= cycleDuration * total) return Position(0, total, 0, true)
        val cycle = (elapsed / cycleDuration).toInt() + 1
        val within = elapsed % cycleDuration
        return if (within < d1) Position(1, cycle, d1 - within, false)
        else Position(2, cycle, cycleDuration - within, false)
    }
}
