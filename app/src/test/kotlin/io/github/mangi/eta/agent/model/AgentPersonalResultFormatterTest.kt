package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPersonalResultFormatterTest {
    @Test
    fun memoryGetPresentsNaturalQueryOrReadStatusWithoutRawLineOrByteNumbers() {
        val querySuccess = summarize("memory_get", """{"matched_lines":3,"line_count":500,"bytes":15000}""")
        assertEquals("found 3 matching memories", querySuccess)
        assertFalse(querySuccess!!.contains("line"))
        assertFalse(querySuccess.contains("bytes"))

        val queryEmpty = summarize("memory_get", """{"matched_lines":0,"line_count":500,"bytes":15000}""")
        assertEquals("no matching memories found", queryEmpty)

        val fullRead = summarize("memory_get", """{"line_count":200,"bytes":8000}""")
        assertEquals("memory read", fullRead)
        assertFalse(fullRead!!.contains("line"))
        assertFalse(fullRead.contains("bytes"))

        val partialRead = summarize("memory_get", """{"has_more":true,"line_count":200,"bytes":8000}""")
        assertEquals("read the first part of memory", partialRead)

        // character memory
        val charQuery = summarize("character_memory_get", """{"matched_lines":2}""")
        assertEquals("found 2 matching story items", charQuery)
        val charEmpty = summarize("character_memory_get", """{"matched_lines":0}""")
        assertEquals("no matching story found", charEmpty)
        val charRead = summarize("character_memory_get", """{}""")
        assertEquals("character memory read", charRead)
    }

    @Test
    fun memoryWritePresentsActionConclusionWithoutByteOrLineCounts() {
        assertEquals("memory saved", summarize("memory_write", """{"mode":"append","bytes":5000,"line_count":120}"""))
        assertEquals("memory updated", summarize("memory_write", """{"mode":"replace_range","bytes":5000,"line_count":120}"""))
        assertEquals("memory cleared", summarize("memory_write", """{"mode":"clear"}"""))

        assertEquals("character memory saved", summarize("character_memory_write", """{"mode":"append"}"""))
        assertEquals("character memory updated", summarize("character_memory_write", """{"mode":"replace_range"}"""))
        assertEquals("character memory cleared", summarize("character_memory_write", """{"mode":"clear"}"""))
    }

    @Test
    fun memoryArgumentsAreCleanAndConcise() {
        assertEquals("append memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"append","content":"abc"}"""))
        assertEquals("update memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"replace_range","content":"abc"}"""))
        assertEquals("clear memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"clear"}"""))
        assertEquals("retrieve memory", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{"query":"coffee preference"}"""))
        assertEquals("read memory", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{}"""))

        assertEquals("append character memory", AgentPersonalResultFormatter.summarizeArguments("character_memory_write", """{"mode":"append"}"""))
        assertEquals("retrieve character memory", AgentPersonalResultFormatter.summarizeArguments("character_memory_get", """{"query":"backstory"}"""))
    }

    @Test
    fun personalSearchesPresentHumanReadableUnitsAndEmptyStates() {
        val cases = listOf(
            Triple("search_system_memories", "found 3 system memories", "no matching system memories found"),
            Triple("search_notes", "found 3 notes", "no matching notes found"),
            Triple("search_calendar_events", "found 3 event", "no matching events found"),
            Triple("search_contacts", "found 3 contacts", "no matching contacts found"),
            Triple("search_messages", "found 3 messages", "no matching messages found"),
            Triple("search_call_history", "found 3 call records", "no call records found"),
            Triple("search_saved_places", "found 3 place", "no matching places found"),
            Triple("search_personal_orders", "found 3 order", "no matching orders found"),
            Triple("search_bills", "found 3 bill", "no bill records found"),
            Triple("search_todos", "found 3 todos", "no todos found"),
            Triple("search_flights", "found 3 flights", "no flight itineraries found"),
            Triple("search_hotels", "found 3 hotel reservations", "no reservation records found"),
            Triple("search_trains", "found 3 trains", "no train itineraries found"),
            Triple("search_daily_events", "found 3 life events", "no life events found"),
            Triple("search_memory_collections", "found 3 memory collections", "no memory collections found"),
            Triple("search_recordings", "found 3 recordings", "no recordings found"),
            Triple("search_recording_summaries", "found 3 recording summaries", "no recording summaries found"),
            Triple("recent_notifications", "found 3 notifications", "no notifications found"),
            Triple("search_clipboard_history", "found 3 clipboard records", "no clipboard records found"),
            Triple("search_downloads", "found 3 download records", "no download records found"),
            Triple("search_media", "found 3 media files", "no matching media files found"),
            Triple("search_qq_chat_images", "found 3 chat images", "no chat images found"),
        )

        cases.forEach { (tool, expectedFound, expectedEmpty) ->
            val found = summarize(tool, """{"items":[{},{},{}]}""")
            assertEquals("Tool $tool found summary mismatch", expectedFound, found)
            val empty = summarize(tool, """{"items":[]}""")
            assertEquals("Tool $tool empty summary mismatch", expectedEmpty, empty)
        }
    }

    @Test
    fun personalSearchWithPaginationAndPartialScopePreservesIndicators() {
        val result = summarize("search_messages", """{"items":[{},{}],"has_more":true,"partial":true}""")
        assertEquals("found 2 messages · more available · collection scope limited", result)
    }

    @Test
    fun verificationCodeAndSpecialTools() {
        assertEquals("verification code read", summarize("read_sms_code", """{"items":[{"code":"123456"}]}"""))
        assertEquals("no latest verification code received", summarize("read_sms_code", """{"items":[]}"""))
        assertEquals("details read", summarize("read_personal_item", """{"id":"1"}"""))
        assertEquals("bills summarized", summarize("summarize_bills", """{"total":100}"""))
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPersonalResultFormatter.summarize(toolName, JSONObject(json).also {
            if (!it.has("ok")) it.put("ok", true)
        })
}
