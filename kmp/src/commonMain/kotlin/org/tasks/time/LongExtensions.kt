package org.tasks.time

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

fun Long.noon(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let { LocalDateTime(it.year, it.month, it.dayOfMonth, 12, 0, 0, 0) }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.startOfDay(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let { LocalDateTime(it.year, it.month, it.dayOfMonth, 0, 0, 0, 0) }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.startOfMinute(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let { LocalDateTime(it.year, it.month, it.dayOfMonth, it.hour, it.minute, 0, 0) }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.startOfSecond(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let {
                LocalDateTime(
                    it.year,
                    it.month,
                    it.dayOfMonth,
                    it.hour,
                    it.minute,
                    it.second,
                    0
                )
            }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.endOfMinute(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let {
                LocalDateTime(
                    it.year,
                    it.month,
                    it.dayOfMonth,
                    it.hour,
                    it.minute,
                    59,
                    999_000_000
                )
            }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.endOfDay(): Long =
    if (this > 0) {
        toLocalDateTime()
            .let { LocalDateTime(it.year, it.month, it.dayOfMonth, 23, 59, 59, 0) }
            .toEpochMilliseconds()
    } else {
        0
    }

fun Long.withMillisOfDay(millisOfDay: Int): Long =
    if (this > 0) {
        LocalDateTime(
            date = toLocalDateTime().date,
            time = LocalTime.fromMillisecondOfDay(millisOfDay)
        )
            .toEpochMilliseconds()
    } else {
        0
    }

val Long.millisOfDay: Int
    get() = if (this > 0) toLocalDateTime().time.toMillisecondOfDay() else 0

private fun Long.toLocalDateTime(): LocalDateTime =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())

private fun LocalDateTime.toEpochMilliseconds(): Long =
    toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
