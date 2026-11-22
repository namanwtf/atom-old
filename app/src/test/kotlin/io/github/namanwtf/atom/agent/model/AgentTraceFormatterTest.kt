package io.github.namanwtf.atom.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceFormatterTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun currentContextIsPresentedAsTimeAndLocationWithoutRequiringAnOkField() {
        assertEquals("read time and location", formatter.summarizeArguments(
            AgentModelClient.ToolCall("context", "get_current_context", "{}"),
        ))
        assertEquals("2026-10-28 12:30 +08:00 · system location is off", formatter.summarizeResult(
            "get_current_context",
            AgentModelClient.ToolResult("""{"datetime":"2026-10-28T12:30:00+08:00","location":{"status":"location_disabled"}}"""),
        ))
    }

    @Test
    fun terminalLifecyclesHaveDistinctLabelsAndKeepCommandRedaction() {
        listOf(
            "terminal" to """{"command":"echo secret-marker"}""",
            "terminal_job" to """{"action":"start","command":"echo secret-marker"}""",
            "terminal_daemon" to """{"action":"start","command":"echo secret-marker"}""",
        ).forEach { (name, args) ->
            val call = AgentModelClient.ToolCall("task", name, args)
            val summary = formatter.summarizeArguments(call)
            assertFalse(summary.contains("secret-marker"))
            val label = when (name) { "terminal_job" -> "async command"; "terminal_daemon" -> "background service"; else -> "terminal" }
            assertTrue(summary, summary.contains(label))
            assertEquals("echo secret-marker", formatter.displayCommand(call))
        }
        val result = formatter.summarizeResult("terminal_job", AgentModelClient.ToolResult(
            """{"ok":true,"tool":"terminal_job","action":"read","stdout":"done","exit_code":0}"""))
        assertTrue(result, result.contains("done"))
    }

    @Test
    fun publicWebArgumentsExposeOnlyActionAndHost() {
        val search = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "search", "web_search", """{"query":"private-query","max_results":3}""",
        ))
        assertEquals("search the web", search)
        val fetch = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "fetch", "fetch_url", """{"url":"https://alice:password@example.com/private-path?token=secret-query#private-fragment"}""",
        ))
        assertEquals("read the page · example.com", fetch)
        val next = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "next", "fetch_url", """{"document_id":"private-document-id","offset_chars":12000}""",
        ))
        assertEquals("continue reading the page", next)
    }

    @Test
    fun publicWebResultsNeverEchoQueryTitlesBodyOrCompleteUrl() {
        val search = formatter.summarizeResult("web_search", AgentModelClient.ToolResult(
            """{"ok":true,"query":"private-query","results":[{"title":"private-title","url":"https://example.com/private-path","snippet":"private-body"}]}""",
        ))
        assertEquals("found 1 search results", search)
        val fetch = formatter.summarizeResult("fetch_url", AgentModelClient.ToolResult(
            """{"ok":true,"final_url":"https://alice:password@example.com/private-path?token=secret-query","title":"private-title","text":"private-body","has_more":true}""",
        ))
        assertEquals("read first 12 chars", fetch)
        listOf("private-query", "private-title", "private-body", "private-path", "password", "secret-query").forEach {
            assertFalse(search.contains(it))
            assertFalse(fetch.contains(it))
        }
    }

    @Test
    fun publicWebFailureSummaryDoesNotEchoServerMessages() {
        listOf("web_search", "fetch_url").forEach { name ->
            val failure = formatter.summarizeResult(name, AgentModelClient.ToolResult(
                """{"ok":false,"code":"HTTP_ERROR","message":"private-query https://alice:password@example.com/private-path?token=secret-query"}""",
            ))
            assertTrue(failure.contains("code=HTTP_ERROR"))
            assertFalse(failure.contains("private"))
            assertFalse(failure.contains("password"))
            assertFalse(failure.contains("secret-query"))
        }
    }

    @Test
    fun sensitiveToolArgumentsAreSummarizedWithoutRawValues() {
        val cases = listOf(
            RedactionCase(
                toolName = "terminal",
                argumentsJson =
                    """{"action":"open_and_exec","identity":"root","command":"echo bearer-secret"}""",
                expectedParts = listOf("terminal", "single execution", "Android", "root"),
                sensitiveParts = listOf("echo bearer-secret", "bearer-secret"),
            ),
            RedactionCase(
                toolName = "run_command",
                argumentsJson = """{"command":"cat /data/local/tmp/private-token"}""",
                expectedParts = listOf("run command", "Android"),
                sensitiveParts = listOf("cat ", "/data/local/tmp/private-token", "private-token"),
            ),
            RedactionCase(
                toolName = "write_file",
                argumentsJson =
                    """{"path":"/data/local/tmp/secret.txt","content":"api-key-value"}""",
                expectedParts = listOf("write file", "character"),
                sensitiveParts = listOf("/data/local/tmp/secret.txt", "api-key-value"),
            ),
            RedactionCase(
                toolName = "input_text",
                argumentsJson = """{"text":"one-time-password-123456"}""",
                expectedParts = listOf("type text", "character"),
                sensitiveParts = listOf("one-time-password-123456", "123456"),
            ),
            RedactionCase(
                toolName = "read_file",
                argumentsJson = """{"path":"/data/user/0/example/private.xml"}""",
                expectedParts = listOf("read file"),
                sensitiveParts = listOf("/data/user/0/example/private.xml", "private.xml"),
            ),
            RedactionCase(
                toolName = "list_directory",
                argumentsJson = """{"path":"/storage/emulated/0/Private"}""",
                expectedParts = listOf("list directory"),
                sensitiveParts = listOf("/storage/emulated/0/Private"),
            ),
            RedactionCase(
                toolName = "memory_get",
                argumentsJson = """{"query":"private relationship"}""",
                expectedParts = listOf("retrieve memory"),
                sensitiveParts = listOf("private relationship"),
            ),
            RedactionCase(
                toolName = "memory_write",
                argumentsJson = """{"mode":"append","revision":"secret-revision","content":"private memory"}""",
                expectedParts = listOf("append memory"),
                sensitiveParts = listOf("secret-revision", "private memory"),
            ),
        )

        cases.forEach { case ->
            val summary = formatter.summarizeArguments(
                AgentModelClient.ToolCall(
                    id = "call-test",
                    name = case.toolName,
                    argumentsJson = case.argumentsJson,
                )
            )

            case.expectedParts.forEach { expected ->
                assertTrue(
                    "${case.toolName} summary must contain '$expected': $summary",
                    summary.contains(expected),
                )
            }
            case.sensitiveParts.forEach { sensitive ->
                assertFalse(
                    "${case.toolName} summary leaked '$sensitive': $summary",
                    summary.contains(sensitive),
                )
            }
        }
    }

    @Test
    fun stateChangeWithoutReadbackIsShownAsUnconfirmed() {
        val result = AgentModelClient.ToolResult(
            content = """{"ok":false,"status":"unconfirmed","verified":false,"code":"STATE_CHANGE_UNCONFIRMED"}""",
        )
        assertEquals("operation submitted, effect unconfirmed", formatter.summarizeResult("set_device_state", result))
        assertFalse(formatter.isSuccessResult(result))
    }

    @Test
    fun deviceStateResultShowsObservedChangesWithoutInventingPreviousState() {
        listOf(
            """{"target":"auto_rotate","before":false,"after":true}""" to "Auto-rotate: off → on",
            """{"target":"auto_brightness","before":true,"after":false}""" to "Auto-brightness: on → off",
            """{"target":"wifi","before":true,"after":true}""" to "Wi-Fi: stays on",
            """{"target":"auto_rotate","before":false,"after":false}""" to "Auto-rotate: stays off",
            """{"target":"auto_rotate","before":null,"after":true}""" to "Auto-rotate: on",
        ).forEach { (observed, expectedSummary) ->
            val result = JSONObject(observed).put("ok", true).put("verified", true)
            assertEquals(expectedSummary, formatter.summarizeResult("set_device_state", AgentModelClient.ToolResult(result.toString())))
        }
    }

    @Test
    fun deviceStateResultRequiresVerifiedBooleanReadback() {
        listOf(
            """{"ok":true,"verified":true,"target":"auto_rotate","expected":true}""" to "done",
            """{"ok":true,"verified":true,"target":"auto_rotate","after":"false"}""" to "done",
            """{"ok":true,"verified":true,"target":"private-target","after":true}""" to "done",
            """{"ok":true,"verified":false,"target":"auto_rotate","before":false,"after":true}""" to "executed, effect unconfirmed",
            """{"ok":false,"status":"unconfirmed","verified":false,"target":"auto_rotate","before":false,"after":false,"expected":true}""" to "operation submitted, effect unconfirmed",
            """{"ok":false,"code":"ROOT_REQUIRED","target":"auto_rotate","before":false,"after":true}""" to "failure · code=ROOT_REQUIRED",
        ).forEach { (content, expectedSummary) ->
            assertEquals(expectedSummary, formatter.summarizeResult("set_device_state", AgentModelClient.ToolResult(content)))
        }
    }

    @Test
    fun skillResultsShowReturnedCountsAndWhenInstalledSkillsBecomeAvailable() {
        val listed = AgentModelClient.ToolResult(
            """{"ok":true,"count":99,"items":[{"name":"private-name","installed":true},{"path":"private-path","installed":false}]}""",
        )
        assertEquals("found 2 skill · 1 installed", formatter.summarizeResult("skills_inspect_github", listed))
        val installed = AgentModelClient.ToolResult(
            """{"ok":true,"installed":[{"name":"private-name"}],"available":"next_turn","message":"private-message"}""",
        )
        assertEquals("installed 1 skill · available next turn", formatter.summarizeResult("skills_install_from_github", installed))
    }

    @Test
    fun genericQueryResultsShowPageSizeWithoutExposingRecords() {
        val result = AgentModelClient.ToolResult(
            """{"ok":true,"count":99,"items":[{"content":"private-record"},{"value":"private-value"}],"has_more":true,"partial":true}""",
            sensitive = true,
        )
        assertEquals("found 2 messages · more available · collection scope limited", formatter.summarizeResult("search_messages", result))

        val generic = AgentModelClient.ToolResult(
            """{"ok":true,"items":[{"content":"one"},{"content":"two"}],"has_more":true,"partial":true}""",
        )
        assertEquals("found 2 results · more available · collection scope limited", formatter.summarizeResult("custom_search", generic))
    }

    @Test
    fun specializedResultsKeepFailureAndSubmissionSemantics() {
        assertEquals("Auto screen-off: 30 sec → 2 min", formatter.summarizeResult("set_screen_timeout", AgentModelClient.ToolResult(
            """{"ok":true,"verified":true,"before":30000,"after":120000}""",
        )))
        assertEquals("timer request submitted · 5 min", formatter.summarizeResult("set_timer", AgentModelClient.ToolResult(
            """{"ok":true,"status":"dispatched","verified":false,"duration_seconds":300}""",
        )))
        assertEquals("appended 512 bytes", formatter.summarizeResult("write_file", AgentModelClient.ToolResult(
            """{"ok":true,"mode":"append","bytes_written":512}""",
        )))
        assertEquals("failure · code=FILE_INPUT_FAILED", formatter.summarizeResult("write_file", AgentModelClient.ToolResult(
            """{"ok":false,"code":"FILE_INPUT_FAILED","mode":"append","bytes_written":512}""",
        )))
    }

    @Test
    fun terminalCommandsAreExposedOnlyThroughDisplayField() {
        val terminal = AgentModelClient.ToolCall(
            id = "terminal-call",
            name = "terminal",
            argumentsJson =
                """{"action":"open_and_exec","environment":"linux","command":"git status --short"}""",
        )
        val runCommand = AgentModelClient.ToolCall(
            id = "run-command-call",
            name = "run_command",
            argumentsJson = """{"command":"pm list packages | head"}""",
        )

        assertEquals("git status --short", formatter.displayCommand(terminal))
        assertEquals("pm list packages | head", formatter.displayCommand(runCommand))
        assertEquals("terminal · single execution · Linux", formatter.summarizeArguments(terminal))
        assertFalse(formatter.summarizeArguments(terminal).contains("git status"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("observe", "observe_screen", "{}")
            )
        )
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall(
                    "oversized",
                    "run_command",
                    """{"command":"${"x".repeat(4_001)}"}""",
                )
            )
        )
    }

    @Test
    fun terminalSummaryPreservesExplicitEnvironmentNames() {
        listOf("android" to "Android", "alpine" to "Alpine", "debian" to "Debian")
            .forEach { (environment, label) ->
                val summary = formatter.summarizeArguments(
                    AgentModelClient.ToolCall(
                        id = "terminal-environment",
                        name = "terminal",
                        argumentsJson =
                            """{"action":"open_and_exec","environment":"$environment"}""",
                    ),
                )

                assertEquals("terminal · single execution · $label", summary)
            }
    }

    @Test
    fun displayedTerminalCommandsRedactCommonCredentialForms() {
        val command = """export API_KEY='sk-secret'; curl -H 'Authorization: Bearer token-value' --password hunter2 https://example.com?access_token=query-secret"""
        val displayed = formatter.displayCommand(
            AgentModelClient.ToolCall(
                id = "secret-command",
                name = "run_command",
                argumentsJson = JSONObject().put("command", command).toString(),
            )
        )!!

        assertTrue(displayed.contains("API_KEY=<hidden>"))
        assertTrue(displayed.contains("Authorization: <hidden>"))
        assertTrue(displayed.contains("--password <hidden>"))
        assertTrue(displayed.contains("access_token=<hidden>"))
        assertFalse(displayed.contains("sk-secret"))
        assertFalse(displayed.contains("token-value"))
        assertFalse(displayed.contains("hunter2"))
        assertFalse(displayed.contains("query-secret"))
    }

    @Test
    fun malformedSensitiveArgumentsUseSafeFallback() {
        val summary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "call-test",
                name = "terminal",
                argumentsJson = "{secret-command",
            )
        )

        assertTrue(summary.contains("terminal"))
        assertFalse(summary.contains("secret-command"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("call-test", "terminal", "{secret-command")
            )
        )
    }

    @Test
    fun screenObservationSummaryUsesTreeFirstDefaults() {
        val defaultSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-default",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val screenshotSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(defaultSummary.contains("observe the screen"))
        assertTrue(defaultSummary.contains("with UI tree"))
        assertFalse(defaultSummary.contains("with screenshot"))
        assertTrue(screenshotSummary.contains("with screenshot"))
        assertTrue(screenshotSummary.contains("with UI tree"))
    }

    @Test
    fun browserResultSummaryIsHumanReadable() {
        val success = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"navigate","page":{"url":"https://example.com/path?token=secret-query","title":"Example Domain"}}""",
            ),
        )

        assertTrue(success.contains("opened"))
        assertTrue(success.contains("example.com"))
        assertTrue(success.contains(""Example Domain""))
        assertFalse(success.contains("ok="))
        assertFalse(success.contains("token=secret-query"))

        val failure = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"USER_CONTROL_ACTIVE","action":"click"}""",
            ),
        )

        assertTrue(failure.startsWith("failure"))
        assertTrue(failure.contains("code=USER_CONTROL_ACTIVE"))

        val readable = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"get_readable","text_chars":23000,"truncated":true,"page":{"url":"https://example.com"}}""",
            ),
        )

        assertTrue(readable.contains("body extracted"))
        assertTrue(readable.contains("about 23k chars"))
        assertTrue(readable.contains("truncated"))
    }

    @Test
    fun resultSuccessComesFromOkFlagNotSummaryText() {
        val ok = AgentModelClient.ToolResult(content = """{"ok":true}""")
        val failed = AgentModelClient.ToolResult(content = """{"ok":false,"code":"X"}""")
        val noFlag = AgentModelClient.ToolResult(content = """{"data":1}""")
        val malformed = AgentModelClient.ToolResult(content = "{broken")

        assertTrue(formatter.isSuccessResult(ok))
        assertFalse(formatter.isSuccessResult(failed))
        assertTrue(formatter.isSuccessResult(noFlag))
        assertTrue(formatter.isSuccessResult(malformed))
        assertEquals("tapped", formatter.summarizeResult("tap", ok))
        assertEquals("failure · code=X", formatter.summarizeResult("tap", failed))
    }

    @Test
    fun memoryResultSummaryPresentsNaturalStatusWithoutByteOrLineCounts() {
        val summary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"bytes":321,"line_count":9,"content":"private memory"}""",
                sensitive = true,
            ),
        )

        assertEquals("memory read", summary)
        assertFalse(summary.contains("9 line"))
        assertFalse(summary.contains("321 bytes"))
        assertFalse(summary.contains("private memory"))

        val querySummary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"matched_lines":3,"line_count":200,"bytes":8000}""",
            ),
        )
        assertEquals("found 3 matching memories", querySummary)
        assertFalse(querySummary.contains("200"))
        assertFalse(querySummary.contains("8000"))
    }

    @Test
    fun searchQueryIsShownInSummary() {
        val searchApps = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-apps",
                name = "search_apps",
                argumentsJson = """{"query":"Douyin"}""",
            ),
        )
        assertEquals("search apps · Douyin", searchApps)

        val searchFiles = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-files",
                name = "search_files",
                argumentsJson = """{"query":"weekly report"}""",
            ),
        )
        assertEquals("search files · weekly report", searchFiles)

        // Single-line and truncate the keyword to avoid blowing up the collapsed row title
        val longQuery = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-long",
                name = "search_apps",
                argumentsJson = """{"query":"${"a very long keyword".repeat(10)}\nsecond line"}""",
            ),
        )
        assertFalse(longQuery.contains("\n"))
        assertTrue(longQuery.length <= "search apps · ".length + 30 + 3)

        // non-search device tools don't append the query
        val deviceStatus = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "device-status",
                name = "device_status",
                argumentsJson = """{"query":"must not appear"}""",
            ),
        )
        assertEquals("view device status", deviceStatus)
    }

    @Test
    fun searchAppsResultListsAppNames() {
        val summary = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """
                    {"ok":true,"tool":"search_apps","query":"Douyin","apps":[
                      {"app_name":"Douyin","package_name":"com.ss.android.ugc.aweme","is_system_app":false},
                      {"app_name":"Douyin Lite","package_name":"com.ss.android.ugc.aweme.lite","is_system_app":false},
                      {"app_name":"Douyin Mall","package_name":"com.ss.android.ugc.livelite","is_system_app":false},
                      {"app_name":"Douyin Huoshan","package_name":"com.ss.android.ugc.live","is_system_app":false}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(summary.contains("found 4 app"))
        assertTrue(summary.contains("Douyin, Douyin Lite, Douyin Mall"))
        assertTrue(summary.contains("etc"))
        assertFalse(summary.contains("com.ss.android"))

        val empty = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"search_apps","query":"nonexistent app","apps":[]}""",
            ),
        )
        assertEquals("no matching apps found", empty)
    }

    @Test
    fun launchAppResultShowsAppName() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"launch_app","app_name":"Douyin","package_name":"com.ss.android.ugc.aweme"}""",
            ),
        )

        assertEquals("opened · Douyin", summary)
    }

    @Test
    fun unconfirmedLaunchDoesNotClaimTheAppHasOpened() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"app_name":"sample app","canvas":"virtual","launch_status":"unconfirmed"}""",
            ),
        )

        assertEquals("open requested, pending confirmation · sample app · virtual display", summary)
    }

    @Test
    fun terminalResultShowsExitCodeAndOutputPreview() {
        val success = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"run_command","exit_code":0,"stdout":"hello\nworld","stderr":"","timed_out":false}""",
            ),
        )
        assertTrue(success.startsWith("execution complete"))
        assertTrue(success.contains("hello\nworld"))

        val failure = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"terminal","action":"exec","exit_code":1,"stdout":"","stderr":"Permission denied"}""",
            ),
        )
        assertTrue(failure.startsWith("failure · exit code 1"))
        assertTrue(failure.contains("Permission denied"))

        val timedOut = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"run_command","exit_code":-2,"timed_out":true,"stdout":""}""",
            ),
        )
        assertEquals("failure · execution timed out", timedOut)

        // protocol errors still keep the code= marker
        val coded = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"JOB_NOT_FOUND"}""",
            ),
        )
        assertEquals("failure · code=JOB_NOT_FOUND", coded)

        // Errors with a Chinese reason: the reason is user-facing, code= is left for log extraction
        val codedWithMessage = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TERMINAL_TOOLS_DISABLED","message":"enable terminal/file tools first"}""",
            ),
        )
        assertEquals("failure · enable terminal/file tools first · code=TERMINAL_TOOLS_DISABLED", codedWithMessage)

        // session action with no output
        val closed = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"terminal","action":"close","closed_session":true}""",
            ),
        )
        assertEquals("terminal · close terminal", closed)

        val multiLineOutput = (1..12).joinToString("\n") { "line-$it" }
        val multiLinePreview = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "terminal")
                    .put("exit_code", 0)
                    .put("stdout", multiLineOutput)
                    .toString(),
            ),
        )
        assertTrue(multiLinePreview.contains("line-10"))
        assertFalse(multiLinePreview.contains("line-11"))
        assertTrue(multiLinePreview.endsWith("\n…"))
    }

    @Test
    fun failureSummaryShowsHumanReasonAndKeepsCodeMarker() {
        val summary = formatter.summarizeResult(
            "wait_for_text",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TIMEOUT","message":"wait for text timed out: Douyin"}""",
            ),
        )
        assertEquals("failure · wait for text timed out: Douyin · code=TIMEOUT", summary)

        val codeOnly = formatter.summarizeResult(
            "tap_element",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"INVALID_NODE_INDEX"}""",
            ),
        )
        assertEquals("failure · code=INVALID_NODE_INDEX", codeOnly)
    }

    @Test
    fun terminalResultPreviewIsBounded() {
        val longOutput = (1..15).joinToString("\n") { "line-$it-${"x".repeat(100)}" }
        val summary = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "run_command")
                    .put("exit_code", 0)
                    .put("stdout", longOutput)
                    .put("stdout_truncated", true)
                    .toString(),
            ),
        )

        val previewLines = summary.lines()
        // status line + at most 10 line preview + ellipsis marker (aligned with ToolActivityDetail's MAX_RESULT_LINES = 12)
        assertTrue(previewLines.size <= 12)
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < longOutput.length)
    }

    private data class RedactionCase(
        val toolName: String,
        val argumentsJson: String,
        val expectedParts: List<String>,
        val sensitiveParts: List<String>,
    )
}
