package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPhoneResultFormatterTest {
    @Test
    fun alarmCreationRequiresVerificationAndIntentOnlyMeansSubmitted() {
        assertEquals("alarm created · 07:30", summarize("set_alarm", """
            {"ok":true,"verified":true,"alarm":{"hour":7,"minute":30,"label":"private-label"}}
        """))
        assertEquals("alarm request submitted · 07:30", summarize("set_alarm", """
            {"ok":true,"status":"dispatched","verified":false,"hour":7,"minute":30}
        """))
        assertEquals("timer request submitted · 1 hour 1 min 1 sec", summarize("set_timer", """
            {"ok":true,"status":"dispatched","verified":false,"duration_seconds":3661}
        """))
        assertNull(summarize("set_alarm", """{"ok":true,"verified":false}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"mode":"ui_fallback"}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"status":"unconfirmed"}"""))
    }

    @Test
    fun missingOrInvalidClockValuesDoNotInventTimeOrDisabledState() {
        listOf("{}", """{"hour":24,"minute":0}""", """{"hour":7,"minute":60}""",
            """{"hour":7.5,"minute":30}""").forEach { alarm ->
            assertEquals("alarm created", summarize("set_alarm", """{"ok":true,"verified":true,"alarm":$alarm}"""))
        }
        listOf(-1, 0, 86401).forEach { seconds ->
            assertEquals("timer request submitted", summarize("set_timer", """
                {"ok":true,"status":"dispatched","duration_seconds":$seconds}
            """))
        }
        assertNull(summarize("set_alarm_enabled", """{"ok":true,"verified":true,"alarm":{}}"""))
        assertEquals("alarm stays off · 07:30", summarize("set_alarm_enabled", """
            {"ok":true,"verified":true,"before":{"enabled":false},"alarm":{"enabled":false,"hour":7,"minute":30}}
        """))
        assertEquals("Alarm time: 07:00 → 07:30 · not enabled", summarize("update_alarm_time", """
            {"ok":true,"verified":true,"before":{"hour":7,"minute":0},"alarm":{"hour":7,"minute":30,"enabled":false}}
        """))
    }

    @Test
    fun calendarSummaryNamesChangedFieldsWithoutExposingContents() {
        val result = summarize("update_calendar_event", """
            {"ok":true,"verified":true,"event_id":"private-id",
             "before":{"title":"private-old","description":"private-body","eventLocation":"private-place","dtstart":"1","dtend":"2","reminders":[{"minutes":"5","method":"1"}]},
             "event":{"title":"private-new","description":"private-body-new","eventLocation":"private-place-new","dtstart":"3","dtend":"4","reminders":[{"minutes":"10","method":"1"}]}}
        """)
        assertEquals("event updated · title, time, location, description, reminders", result)
        assertFalse(result!!.contains("private"))
        assertFalse(result.contains('\n'))
        assertEquals("event unchanged", summarize("update_calendar_event", """
            {"ok":true,"verified":true,
             "before":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"5","method":"1"},{"minutes":"10","method":"1"}]},
             "event":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"10","method":"1"},{"minutes":"5","method":"1"}]}}
        """))
        assertEquals("event updated", summarize("update_calendar_event", """{"ok":true,"verified":true}"""))
        assertEquals("event updated", summarize("update_calendar_event", """
            {"ok":true,"verified":true,"before":{"title":"private-title"},"event":{"title":"private-title"}}
        """))
    }

    @Test
    fun calendarCountsAreOnlyUsedForCreationAndNotesAreRecycled() {
        assertEquals("created 3 event", summarize("create_calendar_events", """{"ok":true,"verified":true,"count":3}"""))
        assertEquals("event deleted", summarize("delete_calendar_event", """{"ok":true,"verified":true,"affected":8}"""))
        assertEquals("note moved to trash", summarize("delete_note", """
            {"ok":true,"verified":true,"mode":"recycle","affected":1,"before":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("note created", summarize("create_note", """
            {"ok":true,"verified":true,"note":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("found 2 alarm · more available", summarize("list_alarms", """
            {"ok":true,"items":[{"label":"private-one"},{"label":"private-two"}],"has_more":true}
        """))
        assertEquals("no alarms", summarize("list_alarms", """{"ok":true,"items":[]}"""))
        assertEquals("found 1 available calendars", summarize("list_calendars", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("no available calendars found", summarize("list_calendars", """{"ok":true,"items":[]}"""))
        assertEquals("found 1 timers", summarize("list_active_timers", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("no active timers", summarize("list_active_timers", """{"ok":true,"items":[]}"""))
    }

    @Test
    fun readsRequireReturnedObjects() {
        assertEquals("note read", summarize("read_note", """{"ok":true,"note":{"content":"private-body"}}"""))
        assertEquals("event read", summarize("read_calendar_event", """{"ok":true,"event":{"title":"private-title"}}"""))
        listOf("read_note", "read_calendar_event").forEach { tool ->
            assertNull(summarize(tool, """{"ok":true}"""))
            assertNull(summarize(tool, """{"ok":true,"note":null,"event":null}"""))
        }
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPhoneResultFormatter.summarize(toolName, JSONObject(json))
}
