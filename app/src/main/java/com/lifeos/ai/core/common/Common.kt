package com.lifeos.ai.core.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** Text that can come either from resources (localized) or from dynamic data (user/AI content). */
sealed interface UiText {
    data class Dynamic(val value: String) : UiText
    class Res(@StringRes val id: Int, vararg val args: Any) : UiText

    fun asString(context: Context): String = when (this) {
        is Dynamic -> value
        is Res -> context.getString(id, *args)
    }

    @Composable
    fun asString(): String = when (this) {
        is Dynamic -> value
        is Res -> stringResource(id, *args)
    }
}

fun newId(): String = UUID.randomUUID().toString()

object TimeUtils {
    fun zone(): ZoneId = ZoneId.systemDefault()

    fun startOfDay(date: LocalDate, zone: ZoneId = zone()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate, zone: ZoneId = zone()): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    fun monthRange(month: YearMonth, zone: ZoneId = zone()): LongRange =
        startOfDay(month.atDay(1), zone)..endOfDay(month.atEndOfMonth(), zone)

    fun toLocalDateTime(epochMillis: Long, zone: ZoneId = zone()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

    fun toLocalDate(epochMillis: Long, zone: ZoneId = zone()): LocalDate =
        toLocalDateTime(epochMillis, zone).toLocalDate()

    fun toEpoch(date: LocalDate, time: LocalTime, zone: ZoneId = zone()): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    fun formatDate(epochMillis: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(toLocalDate(epochMillis))

    fun formatTime(epochMillis: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(toLocalDateTime(epochMillis))

    fun formatDateTime(epochMillis: Long, locale: Locale = Locale.getDefault()): String =
        "${formatDate(epochMillis, locale)}, ${formatTime(epochMillis, locale)}"

    fun formatMonth(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(month)

    fun formatFullDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date)
}

/** Money is stored in minor units (paise) to keep calculations exact. */
object Money {
    fun format(minor: Long, locale: Locale = Locale("en", "IN")): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        nf.currency = java.util.Currency.getInstance("INR")
        nf.maximumFractionDigits = if (minor % 100 == 0L) 0 else 2
        return nf.format(minor / 100.0)
    }

    /** Parses user input like "250", "250.5", "1,200" into minor units, or null if invalid. */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim().replace(",", "").replace("₹", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value.scale() > 2) return null
        if (value > java.math.BigDecimal("100000000")) return null
        return value.movePointRight(2).toLong()
    }

    fun toPlain(minor: Long): String =
        if (minor % 100 == 0L) (minor / 100).toString() else "%.2f".format(Locale.US, minor / 100.0)
}

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T) : LoadState<T>
    data class Error(val message: UiText) : LoadState<Nothing>
}
