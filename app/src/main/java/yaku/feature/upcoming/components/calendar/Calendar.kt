package yaku.feature.upcoming.components.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import io.woong.compose.grid.SimpleGridCells
import io.woong.compose.grid.VerticalGrid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toJavaDayOfWeek
import yaku.core.designsystem.utils.isExpandedWidthWindow
import yaku.core.designsystem.utils.isMediumWidthWindow
import yaku.presentation.core.components.material.padding
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

private val FontSize = 16.sp
private const val DAYS_OF_WEEK = 7

@Composable
fun Calendar(
    selectedYearMonth: YearMonth,
    events: Map<LocalDate, Int>,
    setSelectedYearMonth: (YearMonth) -> Unit,
    onClickDay: (day: LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CalenderHeader(
            yearMonth = selectedYearMonth,
            onPreviousClick = { setSelectedYearMonth(selectedYearMonth.minusMonth()) },
            onNextClick = { setSelectedYearMonth(selectedYearMonth.plusMonth()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.small)
                .padding(start = MaterialTheme.padding.medium),
        )
        CalendarGrid(
            selectedYearMonth = selectedYearMonth,
            events = events,
            onClickDay = onClickDay,
        )
    }
}

@Composable
private fun CalendarGrid(
    selectedYearMonth: YearMonth,
    events: Map<LocalDate, Int>,
    onClickDay: (day: LocalDate) -> Unit,
) {
    // Read through LocalConfiguration rather than Locale.getDefault(): the latter is invisible
    // to Compose, so changing the system language left the calendar on the old locale until the
    // screen was recreated. The remember also has to be keyed, or the recomposition changes
    // nothing.
    val locale = LocalConfiguration.current.locales[0]
    val localeFirstDayOfWeek = WeekFields.of(locale).firstDayOfWeek.value
    val weekDays = remember(localeFirstDayOfWeek) {
        (0 until DAYS_OF_WEEK)
            .map { DayOfWeek.of((localeFirstDayOfWeek - 1 + it) % DAYS_OF_WEEK + 1) }
    }

    val emptyFieldCount = weekDays.indexOf(selectedYearMonth.firstDay.dayOfWeek.toJavaDayOfWeek())
    val daysInMonth = selectedYearMonth.numberOfDays

    VerticalGrid(
        columns = SimpleGridCells.Fixed(DAYS_OF_WEEK),
        modifier = if (isMediumWidthWindow() && !isExpandedWidthWindow()) {
            Modifier.widthIn(max = 360.dp)
        } else {
            Modifier
        },
    ) {
        weekDays.fastForEach { item ->
            Text(
                text = item.getDisplayName(
                    TextStyle.NARROW,
                    locale,
                ),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                fontSize = FontSize,
            )
        }
        repeat(emptyFieldCount) { Box { } }
        repeat(daysInMonth) { dayIndex ->
            val localDate = LocalDate(selectedYearMonth.year, selectedYearMonth.month, dayIndex + 1)
            CalendarDay(
                date = localDate,
                onDayClick = { onClickDay(localDate) },
                events = events[localDate] ?: 0,
            )
        }
    }
}
