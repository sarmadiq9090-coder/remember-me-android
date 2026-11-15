package com.fastkiwi.rememberme.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fastkiwi.rememberme.alarm.Engine
import com.fastkiwi.rememberme.MainActivity
import com.fastkiwi.rememberme.Repo
import com.fastkiwi.rememberme.Screen
import com.fastkiwi.rememberme.core.T
import com.fastkiwi.rememberme.data.IcsAlarm
import com.fastkiwi.rememberme.data.IcsImport
import com.fastkiwi.rememberme.data.IcsItem
import com.fastkiwi.rememberme.data.IcsNote
import com.fastkiwi.rememberme.data.IcsRulePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** F-085 + F-100/F-102: the calendar import report reads in Arabic, from the importer's structured notes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class IcsReportTextTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = Instant.parse("2026-11-15T07:00:00Z")

    @Before fun setUp() {
        T.ids()
        Engine.drain()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Repo.resetForTests()
        File(app.filesDir, "data").deleteRecursively()
        for (name in listOf("device", "filters")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Repo.prefs(app).onboarded = true
        Repo.prefs(app).notifAsked = true
    }

    @After fun settle() {
        Thread.sleep(300)
        Engine.drain()
    }

    private val latinTitle = "Team standup"
    private val arabicTitle = "دفع الإيجار"
    private val zones = listOf("America/New_York", "Asia/Baghdad", "Fiji Standard Time", "UTC")
    /** A rule part with no Arabic name is named as the file writes it (isolated), like a zone id. */
    private val unknownKey = "X-FOO"

    private val titled = IcsItem(latinTitle, todo = false, number = 1)
    private val arabic = IcsItem(arabicTitle, todo = true, number = 2)
    private val untitledEvent = IcsItem(null, todo = false, number = 3, uid = "abc@google.com")
    private val untitledTask = IcsItem(null, todo = true, number = 4)

    private val allParts: List<IcsRulePart> = listOf(
        IcsRulePart.Garbled("garbage"), IcsRulePart.Twice("FREQ"),
        IcsRulePart.Unsupported("COUNT", "5"), IcsRulePart.Unsupported("UNTIL", "20271231T000000Z"), IcsRulePart.Unsupported("BYHOUR", "9,17"),
        IcsRulePart.Unsupported("BYMINUTE", "30"), IcsRulePart.Unsupported("BYSECOND", "1"), IcsRulePart.Unsupported("BYWEEKNO", "1"),
        IcsRulePart.Unsupported("BYYEARDAY", "100"), IcsRulePart.Unsupported("RSCALE", "HIJRI"), IcsRulePart.Unsupported(unknownKey, "1"),
        IcsRulePart.NoFreq, IcsRulePart.BadValue("INTERVAL", "0"), IcsRulePart.BadValue("BYDAY", "1MO"), IcsRulePart.BadValue("BYMONTHDAY", "0"),
        IcsRulePart.NotWithFreq("BYDAY", "MO", "HOURLY"), IcsRulePart.NotWithFreq("BYMONTHDAY", "1", "DAILY"), IcsRulePart.NotWithFreq("BYMONTH", "1", "WEEKLY"),
        IcsRulePart.NotWithFreq("BYSETPOS", "1", "MONTHLY"), IcsRulePart.NotWithFreq("BYMONTH", "1", "MONTHLY"),
        IcsRulePart.HourlyAllDay, IcsRulePart.EveryHours(36), IcsRulePart.EveryDaysOnDays(2), IcsRulePart.EveryDays(400),
        IcsRulePart.EveryWeeksOnDays(2, "MO,TH"), IcsRulePart.EveryWeeks(60), IcsRulePart.EveryMonthsOrYears(false, 2), IcsRulePart.EveryMonthsOrYears(true, 4),
        IcsRulePart.OtherMonth("3", 1), IcsRulePart.SetPos("2"), IcsRulePart.MonthDays(listOf(1, 15, -1, -2), null, 4),
        IcsRulePart.MonthDays(listOf(28, 29, 30, 31), "-1", 4), IcsRulePart.Freq("MINUTELY"), IcsRulePart.Freq("SECONDLY"), IcsRulePart.Freq("FORTNIGHTLY"),
        IcsRulePart.SeveralRules(2), IcsRulePart.Extra("EXRULE"), IcsRulePart.Extra("RDATE"), IcsRulePart.Extra("EXDATE"),
    )

    private val allAlarms: List<IcsAlarm> = listOf(
        IcsAlarm.NoTrigger, IcsAlarm.BadTime("x"), IcsAlarm.FromEnd("-PT5M"), IcsAlarm.BadTrigger("soon"),
        IcsAlarm.AfterStart(300), IcsAlarm.AfterStart(7_200), IcsAlarm.NotOffered(1_200), IcsAlarm.NotOffered(90), IcsAlarm.NotOffered(2 * 86_400),
    )

    /** One of every kind of note (each alarm and rule part too), about items with and without titles. */
    private fun everyKind(): List<IcsNote> = listOf(
        IcsNote.TooBig(50_000, 200_000), IcsNote.CutOff, IcsNote.NotItems(mapOf("VJOURNAL" to 1, "VFREEBUSY" to 2, "VAVAILABILITY" to 3)),
        IcsNote.TooMany(5_000, 2), IcsNote.TooMany(5_000, 1), IcsNote.TooMany(5_000, 11),
        IcsNote.AllDay(listOf(titled, arabic, untitledEvent, untitledTask, titled, arabic, titled), LocalTime.of(9, 0)), IcsNote.AllDay(listOf(titled), LocalTime.of(9, 0)),
        IcsNote.UnknownZone("Fiji Standard Time", "Asia/Baghdad"),
        IcsNote.ChangedOccurrence(titled), IcsNote.TitleShortened(titled, 200), IcsNote.BodyShortened(arabic, 20_000), IcsNote.NoStart(untitledEvent),
        IcsNote.ExtraDatesOnly(titled), IcsNote.RepeatWithoutTime(untitledTask), IcsNote.MonthEnd(titled, 30), IcsNote.LeapDay(arabic),
        IcsNote.ClockDiffers(titled, "America/New_York", "Asia/Baghdad"), IcsNote.ClockDiffers(titled, "UTC", "Asia/Baghdad"),
        IcsNote.RuleNotKept(titled, allParts), IcsNote.StartsLater(titled, 31, LocalDate.of(2027, 3, 31), LocalDate.of(2027, 2, 28)),
        IcsNote.AlarmsWithoutTime(untitledTask), IcsNote.TooManyAlarms(titled, listOf(5, 15, 30), listOf(60, 1440)),
        IcsNote.Incomplete(untitledEvent), IcsNote.Incomplete(untitledTask), IcsNote.NoTitle(untitledEvent), IcsNote.Cancelled(titled),
        IcsNote.Completed(arabic), IcsNote.Duplicate(titled), IcsNote.BadTime(titled, "20270230T090000"), IcsNote.OutOfRange(titled, LocalDate.of(3001, 1, 1)),
        IcsNote.Passed(titled, LocalDateTime.of(2025, 1, 1, 13, 0)), IcsNote.Passed(titled, LocalDateTime.of(2026, 1, 4, 9, 0), listOf(IcsRulePart.Unsupported("COUNT", "40"))),
    ) + allAlarms.map { IcsNote.AlarmNotKept(titled, it) } + allParts.map { IcsNote.RuleNotKept(arabic, listOf(it)) }

    private val latin = Regex("[A-Za-z]")
    private val easternDigits = Regex("[٠-٩۰-۹]")

    /** The line with the allowed Latin runs (item titles, zone ids) taken out. */
    private fun withoutAllowed(line: String, allowed: Collection<String>): String =
        allowed.sortedByDescending { it.length }.fold(line) { s, a -> s.replace(a, "") }

    private fun assertArabic(line: String, allowed: Collection<String>, what: Any) {
        val rest = withoutAllowed(line, allowed)
        assertFalse("Latin in «$line» ($what)", latin.containsMatchIn(rest))
        assertFalse("Arabic-Indic digits in «$line» ($what)", easternDigits.containsMatchIn(line))
        assertFalse("« · » in «$line» ($what)", line.contains("·"))
        assertTrue("no Arabic in «$line» ($what)", line.any { it in 'ء'..'ي' })
    }

    @Test fun everyKindRendersInArabicWithLatinOnlyInTitlesAndZoneIds() {
        for (n in everyKind()) assertArabic(IcsReportText.line(app, n), listOf(latinTitle, unknownKey) + zones, n)
    }

    @Test fun titlesAreIsolatedQuotedAndUntitledItemsAreNumbered() {
        val line = IcsReportText.line(app, IcsNote.Cancelled(titled))
        assertTrue(line, line.startsWith("«") && line.contains(latinTitle))
        // The Latin title is wrapped in direction marks, so it cannot reorder the Arabic around it.
        val inner = line.substringAfter("«").substringBefore("»")
        assertTrue(inner.length > latinTitle.length)
        assertEquals("الحدث رقم 3: بلا عنوان.", IcsReportText.line(app, IcsNote.NoTitle(untitledEvent)))
        assertEquals("المهمة رقم 4: ينتهي الملف داخل هذه المهمة، فقد تكون ناقصة.", IcsReportText.line(app, IcsNote.Incomplete(untitledTask)))
    }

    @Test fun listsUseTheArabicSeparatorPluralsAndArabicDates() {
        val alarms = IcsReportText.line(app, IcsNote.TooManyAlarms(arabic, listOf(5, 15, 60), listOf(1440)))
        assertTrue(alarms, alarms.contains("5 دقائق، 15 دقيقة، ساعة") && alarms.contains("(يوم قبل الموعد)"))
        val parts = IcsReportText.line(app, IcsNote.RuleNotKept(arabic, listOf(IcsRulePart.Unsupported("COUNT", "5"), IcsRulePart.Extra("EXDATE"))))
        assertTrue(parts, parts.contains("(عدد محدد من المرات، تواريخ مستثناة)"))
        val later = IcsReportText.line(app, IcsNote.StartsLater(arabic, 31, LocalDate.of(2027, 3, 31), LocalDate.of(2027, 2, 28)))
        assertTrue(later, later.contains("31") && later.contains("2027") && !later.contains("2027-"))
        val allDay = IcsReportText.line(app, IcsNote.AllDay(List(7) { arabic }, LocalTime.of(9, 0)))
        assertTrue(allDay, allDay.startsWith("7 عناصر ليوم كامل") && allDay.contains("9:00") && allDay.endsWith("وعنصران آخران."))
        assertEquals("قُرئ أول 5,000 حدث ومهمة فقط، ولم يُستورد عنصران غيرها.", IcsReportText.line(app, IcsNote.TooMany(5_000, 2)))
        val passed = IcsReportText.line(app, IcsNote.Passed(arabic, LocalDateTime.of(2025, 1, 1, 13, 0)))
        assertTrue(passed, passed.contains("2025") && (passed.contains("1:00") || passed.contains("13:00")))
    }

    @Test fun englishComesFromTheEnglishTable() {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val en = app.createConfigurationContext(config)
        val line = IcsReportText.line(en, IcsNote.TitleShortened(titled, 200))
        assertTrue(line, line.contains(latinTitle) && line.endsWith("the title is shortened to 200 characters."))
        assertTrue(IcsReportText.line(en, IcsNote.NoTitle(untitledTask)).startsWith("The task #4: "))
    }

    /** A real file's report: every line Arabic (titles and zone ids aside), also for hostile input. */
    @Test fun realFilesRenderInArabic() {
        fun ev(title: String, vararg l: String) = listOf("BEGIN:VEVENT", "UID:$title@t", "SUMMARY:$title") + l + "END:VEVENT"
        val titles = listOf("Berlin call", "Old course", "Many alarms", "Rent", "Leap", "Moved", "Fiji", "No start", "T".repeat(60) + "…")
        val text = (listOf("BEGIN:VCALENDAR", "VERSION:2.0") +
            ev("Berlin call", "DTSTART;TZID=Europe/Berlin:20270615T183000", "RRULE:FREQ=WEEKLY;COUNT=10;BYDAY=TU", "EXDATE:20270622T163000Z") +
            ev("Old course", "DTSTART;TZID=Asia/Baghdad:20260104T090000", "RRULE:FREQ=WEEKLY;COUNT=40") +
            ev("Many alarms", "DTSTART:20270104T090000Z", "BEGIN:VALARM", "TRIGGER:-PT20M", "END:VALARM", "BEGIN:VALARM", "TRIGGER:PT5M", "END:VALARM",
                *listOf("-P1D", "-PT1H", "-PT15M", "-PT5M").flatMap { listOf("BEGIN:VALARM", "TRIGGER:$it", "END:VALARM") }.toTypedArray()) +
            ev("Rent", "DTSTART;TZID=Asia/Baghdad:20270130T090000", "RRULE:FREQ=MONTHLY") +
            ev("Leap", "DTSTART;VALUE=DATE:19960229", "RRULE:FREQ=YEARLY") +
            ev("Moved", "RECURRENCE-ID:20270105T090000Z", "DTSTART:20270106T090000Z") +
            ev("Fiji", "DTSTART;TZID=Fiji Standard Time:20261201T090000") +
            ev("No start") +
            ev("T".repeat(250), "DTSTART:20270104T090000Z") +
            listOf("BEGIN:VEVENT", "UID:x", "DTSTART:20270104T090000Z", "END:VEVENT", "BEGIN:VJOURNAL", "SUMMARY:diary", "END:VJOURNAL", "END:VCALENDAR")
            ).joinToString("\r\n")
        val r = IcsImport.read(text, zone, now)
        assertTrue(r.warnings.size >= 8)
        for (n in r.skipped + r.warnings) assertArabic(IcsReportText.line(app, n), titles + zones + listOf("Europe/Berlin"), n.english())
        // Mutated files: rendering never throws and stays Arabic (any title in the file aside).
        val rnd = Random(85)
        repeat(300) {
            val sb = StringBuilder(text)
            repeat(1 + rnd.nextInt(6)) {
                val p = rnd.nextInt(sb.length + 1)
                when (rnd.nextInt(3)) {
                    0 -> sb.delete(p, minOf(sb.length, p + rnd.nextInt(30)))
                    1 -> sb.insert(p, listOf("\r\n", ";", ":", "RRULE:FREQ=", "TRIGGER:-PT", "BYDAY=1MO", "COUNT=2", "\r\nBEGIN:VALARM\r\n").random(rnd))
                    else -> sb.insert(p, rnd.nextInt(10).toString())
                }
            }
            val res = try { IcsImport.read(sb.toString(), zone, now) } catch (_: IcsImport.ImportException) { return@repeat }
            for (n in res.skipped + res.warnings) {
                val line = IcsReportText.line(app, n)
                assertFalse(line, easternDigits.containsMatchIn(line))
                val allowed = listOfNotNull(n.item?.title) + zones + listOf("Europe/Berlin") +
                    ((n as? IcsNote.UnknownZone)?.tzid?.let { listOf(it) } ?: emptyList()) +
                    ((n as? IcsNote.ClockDiffers)?.zone?.let { listOf(it) } ?: emptyList()) +
                    ((n as? IcsNote.RuleNotKept)?.parts.orEmpty() + (n as? IcsNote.Passed)?.parts.orEmpty())
                        .filterIsInstance<IcsRulePart.Unsupported>().map { it.key } +
                    ((n as? IcsNote.AllDay)?.items.orEmpty().mapNotNull { it.title })
                assertFalse("$line (${n.english()})", latin.containsMatchIn(withoutAllowed(line, allowed)))
            }
        }
    }

    /** The settings dialog shows the Arabic lines, not the importer's English. */
    @Test fun theImportDialogShowsTheReportInArabic() {
        val text = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0",
            "BEGIN:VEVENT", "UID:a", "SUMMARY:Berlin call", "DTSTART;TZID=Asia/Baghdad:20270615T183000", "RRULE:FREQ=WEEKLY;COUNT=10", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b", "SUMMARY:Old meeting", "DTSTART:20250101T100000Z", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val result = IcsImport.read(text, zone, now)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.push(Screen.Settings); it.flows.showIcsReport(result) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("قاعدة تكراره غير مدعومة", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("عدد محدد من المرات", substring = true).assertExists()
            rule.onNodeWithText("مضى موعده", substring = true).assertExists()
            rule.onNodeWithText("سيُضاف تذكير واحد").assertExists()
            for (n in rule.onAllNodes(hasText("•", substring = true)).fetchSemanticsNodes()) {
                val t = n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
                assertFalse(t, latin.containsMatchIn(withoutAllowed(t, listOf("Berlin call", "Old meeting"))))
            }
        }
    }
}
