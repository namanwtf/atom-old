package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskQuietHours
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskScheduleCalculatorTest {
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, shanghai).toInstant().toEpochMilli()

    @Test
    fun dailySchedulePicksTodayOrTomorrow() {
        val schedule = ScheduledTaskSchedule(kind = ScheduledTaskTriggerKind.DAILY, hour = 22, minute = 30)
        val morning = zdt(2026, 11, 19, 9, 0)
        val lateNight = zdt(2026, 11, 19, 22, 30)

        assertEquals(zdt(2026, 11, 19, 22, 30), ScheduledTaskScheduleCalculator.computeNextRunAt(schedule, morning, defaultZone = shanghai))
        assertEquals(zdt(2026, 11, 20, 22, 30), ScheduledTaskScheduleCalculator.computeNextRunAt(schedule, lateNight, defaultZone = shanghai))
    }

    @Test
    fun weekdaysScheduleSkipsWeekend() {
        // 2026-11-20 is a Friday
        val fridayAfter = zdt(2026, 11, 20, 10, 0)
        val schedule = ScheduledTaskSchedule(kind = ScheduledTaskTriggerKind.WEEKDAYS, hour = 8, minute = 30)
        // the next business day is Monday 2026-11-23
        assertEquals(zdt(2026, 11, 23, 8, 30), ScheduledTaskScheduleCalculator.computeNextRunAt(schedule, fridayAfter, defaultZone = shanghai))
    }

    @Test
    fun cronExpressionSupportsStepsRangesAndSundayAlias() {
        val parsed = ScheduledTaskScheduleCalculator.parseCron("*/15 9-17 * * 1-5")
        assertNotNull(parsed)
        assertEquals(setOf(0, 15, 30, 45), parsed!!.minutes)
        assertEquals((9..17).toSet(), parsed.hours)
        assertEquals(setOf(1, 2, 3, 4, 5), parsed.daysOfWeek)

        val sunday = ScheduledTaskScheduleCalculator.parseCron("0 20 * * 7")
        assertNotNull(sunday)
        assertEquals(setOf(0), sunday!!.daysOfWeek)

        // After Friday 2026-11-20 17:45, the next */15 9-17 * * 1-5 should land on Monday 09:00
        val schedule = ScheduledTaskSchedule(
            kind = ScheduledTaskTriggerKind.CRON,
            cronExpression = "*/15 9-17 * * 1-5",
        )
        assertEquals(
            zdt(2026, 11, 23, 9, 0),
            ScheduledTaskScheduleCalculator.computeNextRunAt(schedule, zdt(2026, 11, 20, 17, 45), defaultZone = shanghai),
        )
    }

    @Test
    fun invalidCronIsRejected() {
        assertNull(ScheduledTaskScheduleCalculator.parseCron("0 9 * *"))
        assertNull(ScheduledTaskScheduleCalculator.parseCron("60 9 * * *"))
        assertNull(ScheduledTaskScheduleCalculator.parseCron("0 25 * * *"))
        assertNull(ScheduledTaskScheduleCalculator.parseCron("0 9 * * */0"))
    }

    @Test
    fun quietHoursOvernightWindowSkipsNightIntervalRuns() {
        val quiet = ScheduledTaskQuietHours(enabled = true, startHour = 23, startMinute = 0, endHour = 7, endMinute = 30)
        assertTrue(ScheduledTaskScheduleCalculator.isInQuietHours(zdt(2026, 11, 19, 23, 30), quiet, shanghai))
        assertTrue(ScheduledTaskScheduleCalculator.isInQuietHours(zdt(2026, 11, 20, 6, 0), quiet, shanghai))
        assertFalse(ScheduledTaskScheduleCalculator.isInQuietHours(zdt(2026, 11, 20, 7, 30), quiet, shanghai))

        val interval = ScheduledTaskSchedule(kind = ScheduledTaskTriggerKind.INTERVAL, intervalMinutes = 60)
        val next = ScheduledTaskScheduleCalculator.computeNextRunAt(
            schedule = interval,
            afterMs = zdt(2026, 11, 19, 22, 30),
            quietHours = quiet,
            defaultZone = shanghai,
        )
        assertEquals(zdt(2026, 11, 20, 7, 30), next)
    }

    @Test
    fun misfirePolicyRespectsWindowAndSkip() {
        val baseTask = ScheduledTask(
            id = "t1",
            name = "morning briefing",
            prompt = "compile the morning briefing",
            misfirePolicy = ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW,
            misfireWindowMinutes = 120,
        )
        val scheduled = zdt(2026, 11, 19, 8, 0)
        assertEquals(
            ScheduledTaskScheduleCalculator.MisfireDecision.RunNow,
            ScheduledTaskScheduleCalculator.evaluateMisfire(baseTask, scheduled, zdt(2026, 11, 19, 8, 1), shanghai),
        )
        assertEquals(
            ScheduledTaskScheduleCalculator.MisfireDecision.RunCatchUp,
            ScheduledTaskScheduleCalculator.evaluateMisfire(baseTask, scheduled, zdt(2026, 11, 19, 9, 30), shanghai),
        )
        assertTrue(
            ScheduledTaskScheduleCalculator.evaluateMisfire(baseTask, scheduled, zdt(2026, 11, 19, 11, 0), shanghai)
                is ScheduledTaskScheduleCalculator.MisfireDecision.Skip,
        )
        val skipTask = baseTask.copy(misfirePolicy = ScheduledTaskMisfirePolicy.SKIP)
        assertTrue(
            ScheduledTaskScheduleCalculator.evaluateMisfire(skipTask, scheduled, zdt(2026, 11, 19, 8, 10), shanghai)
                is ScheduledTaskScheduleCalculator.MisfireDecision.Skip,
        )
    }

    @Test
    fun taskPolicyIntersectsGlobalPrefsInsteadOfModelConfigDefaults() {
        // Simulate the default ModelConfig returned by RuntimeConfigRepository.buildRuntimeConfig() (sensitive read / terminal default to false)
        val base = AgentModelClient.ModelConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        )
        val task = ScheduledTask(
            id = "briefing",
            name = "daily evening briefing",
            prompt = "compile the briefing",
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = true,
                mcpTools = false,
                guiTools = false,
            ),
        )
        val resolved = ScheduledTaskRunner.applyTaskPolicyToModelConfig(
            base = base,
            task = task,
            globalThinkingEnabled = true,
            globalBrowserTools = true,
            globalTerminalTools = true,
            globalDeviceDirectTools = true,
            globalDeviceSensitiveReadTools = true,
            globalDeviceSensitiveActionTools = true,
            globalSubagentTools = true,
        )
        assertTrue(resolved.browserTools)
        assertTrue(resolved.terminalTools)
        assertTrue(resolved.deviceDirectTools)
        assertTrue(resolved.deviceSensitiveReadTools)
        assertFalse(resolved.deviceSensitiveActionTools)
        assertTrue(resolved.subagentTools)
    }
}
