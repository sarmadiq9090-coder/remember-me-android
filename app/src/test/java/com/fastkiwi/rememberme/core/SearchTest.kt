package com.fastkiwi.rememberme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** F-065 (one folding matcher with filters, 5,000 tasks under 100 ms) and F-061 (computed states). */
class SearchTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val now = at(zone, 2026, 11, 14, 10)

    private fun task(
        id: String, title: String, body: String = "", due: Instant? = null, done: Boolean = false, trashed: Boolean = false,
        category: Category = Category.PERSONAL, priority: Priority = Priority.NORMAL, tags: List<String> = emptyList(), snoozed: Boolean = false,
    ) = Task(
        id = id, title = title, body = body, dueAt = due, category = category, priority = priority,
        completedAt = if (done) now.minusSeconds(60) else null, deletedAt = if (trashed) now.minusSeconds(60) else null,
        snoozedAt = if (snoozed) now.minusSeconds(120) else null, createdAt = now, updatedAt = now,
    ).withTags(tags)

    private val words = Search.Words(
        categories = mapOf(Category.PERSONAL to "شخصي", Category.WORK to "عمل", Category.STUDY to "دراسة"),
        states = mapOf(Search.State.SNOOZED to "مؤجّل", Search.State.OVERDUE to "متأخر", Search.State.DONE to "منجز"),
    )

    private fun ids(list: List<Task>) = list.map { it.id }.toSet()

    @Test fun statesAreComputedAndOverdueIsNeverDone() {
        assertEquals(Search.State.UPCOMING, Search.stateOf(task("a", "x", due = now.plusSeconds(60)), now))
        assertEquals(Search.State.UPCOMING, Search.stateOf(task("a", "x"), now))
        assertEquals(Search.State.DUE, Search.stateOf(task("a", "x", due = now.minusSeconds(30)), now))
        assertEquals(Search.State.OVERDUE, Search.stateOf(task("a", "x", due = now.minusSeconds(3600)), now))
        assertEquals(Search.State.SNOOZED, Search.stateOf(task("a", "x", due = now.plusSeconds(600), snoozed = true), now))
        assertEquals(Search.State.DONE, Search.stateOf(task("a", "x", due = now.minusSeconds(3600), done = true), now))
        assertEquals(Search.State.TRASHED, Search.stateOf(task("a", "x", done = true, trashed = true), now))
    }

    @Test fun foldsHamzaTaaMarbutaAlefMaqsuraAndDiacritics() {
        val tasks = listOf(task("1", "دفع الإيجار"), task("2", "مُراجَعة المكتبة"), task("3", "موعد مستشفى"), task("4", "شيء آخر"))
        assertEquals(setOf("1"), ids(Search.run(tasks, Search.Query("الايجار"), now)))
        assertEquals(setOf("2"), ids(Search.run(tasks, Search.Query("مراجعه"), now)))
        assertEquals(setOf("3"), ids(Search.run(tasks, Search.Query("مستشفي"), now)))
        assertEquals(setOf("2"), ids(Search.run(tasks, Search.Query("المكتـــبة"), now)))
        // The stored text is never changed.
        assertEquals("دفع الإيجار", tasks[0].title)
    }

    @Test fun matchesNoteTagsCategoryAndStateEveryWordSomewhere() {
        val tasks = listOf(
            task("n", "اتصال", body = "رقم الكهربائي في الملاحظة"),
            task("t", "تسوق", tags = listOf("منزل")),
            task("c", "تقرير", category = Category.WORK),
            task("s", "دواء", due = now.plusSeconds(600), snoozed = true),
        )
        assertEquals(setOf("n"), ids(Search.run(tasks, Search.Query("الكهربائي"), now, words)))
        assertEquals(setOf("t"), ids(Search.run(tasks, Search.Query("منزل"), now, words)))
        assertEquals(setOf("c"), ids(Search.run(tasks, Search.Query("عمل"), now, words)))
        assertEquals(setOf("s"), ids(Search.run(tasks, Search.Query("مؤجل"), now, words)))
        // Every word must match, each anywhere.
        assertEquals(setOf("c"), ids(Search.run(tasks, Search.Query("تقرير عمل"), now, words)))
        assertTrue(Search.run(tasks, Search.Query("تقرير منزل"), now, words).isEmpty())
    }

    @Test fun filtersByStateCategoryTagDateRangeDoneAndTrash() {
        val tasks = listOf(
            task("late", "a", due = now.minusSeconds(7200)),
            task("soon", "b", due = now.plusSeconds(3600), category = Category.STUDY, tags = listOf("#Home")),
            task("next", "c", due = now.plusSeconds(10 * 86_400), priority = Priority.HIGH),
            task("done", "d", due = now.minusSeconds(3600), done = true),
            task("bin", "e", trashed = true),
            task("free", "f"),
        )
        assertEquals(setOf("late"), ids(Search.run(tasks, Search.Query(states = setOf(Search.State.OVERDUE)), now)))
        assertEquals(setOf("soon"), ids(Search.run(tasks, Search.Query(category = Category.STUDY), now)))
        assertEquals(setOf("soon"), ids(Search.run(tasks, Search.Query(tag = "home"), now)))
        assertEquals(setOf("late", "next", "done", "free"), ids(Search.run(tasks, Search.Query(untagged = true), now)))
        assertEquals(setOf("next"), ids(Search.run(tasks, Search.Query(priority = Priority.HIGH), now)))
        assertEquals(setOf("late", "soon", "done"), ids(Search.run(tasks, Search.Query(from = now.minusSeconds(86_400), to = now.plusSeconds(86_400)), now)))
        assertEquals(setOf("late", "soon", "next", "free"), ids(Search.run(tasks, Search.Query(includeDone = false), now)))
        assertEquals(setOf("bin"), ids(Search.run(tasks, Search.Query(states = setOf(Search.State.TRASHED), includeTrash = true), now)))
        // Open ones first, by time; done after them.
        assertEquals(listOf("late", "soon", "next", "free", "done"), Search.run(tasks, Search.Query(), now).map { it.id })
    }

    @Test fun theWeekFollowsTheUsersFirstDay() {
        // 14 November 2026 is a Saturday.
        val (from, to) = Search.weekRange(now, zone, 6)
        assertEquals(at(zone, 2026, 11, 14, 0), from)
        assertEquals(at(zone, 2026, 11, 21, 0), to)
        assertEquals(at(zone, 2026, 11, 8, 0), Search.weekRange(now, zone, 0).first)
        assertEquals(at(zone, 2026, 11, 9, 0), Search.weekRange(now, zone, 1).first)
    }

    @Test fun fiveThousandTasksUnderAHundredMilliseconds() {
        val tasks = (0 until 5_000).map { i ->
            task("t$i", "تذكير رقم $i ${if (i % 7 == 0) "الإيجار" else "شراء"}", body = "ملاحظة طويلة نسبيًا عن التذكير رقم $i ".repeat(4),
                due = now.plusSeconds(i * 600L), category = Category.entries[i % 3], tags = if (i % 5 == 0) listOf("منزل", "عمل$i") else emptyList())
        }
        val q = Search.Query("الايجار منزل")
        repeat(5) { Search.run(tasks, q, now, words) } // warm the JIT
        val runs = (0 until 5).map {
            val start = System.nanoTime()
            val r = Search.run(tasks, q, now, words)
            assertEquals(tasks.indices.count { i -> i % 35 == 0 }, r.size)
            (System.nanoTime() - start) / 1_000_000.0
        }
        assertTrue("search took ${runs.min()} ms", runs.min() < 100.0)
    }
}
