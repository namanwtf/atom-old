package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    val names = setOf("terminal", "terminal_job", "terminal_daemon")

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "terminal",
                description = "Execute a shell command; just provide command. The default environment, identity, and working directory are in the current run context." +
                    "When you need to preserve shell state such as cd or environment variables, first use action=open, then run commands via session_id, and finally action=close." +
                    "The process is reclaimed after a single command ends; do not use & to run in the background. Use terminal_job for long-running or background commands and terminal_daemon for background services that span runs." +
                    "Prefer the corresponding file tools for reading, writing, editing, and searching known files.",
                parameters = parameters(
                    commandProperties()
                        .put("action", action("exec", "open", "close", "check_root")
                            .put("description", "Default exec runs a command; open creates a persistent shell; close closes the session; check_root runs a fixed su -c 'id -u' to detect and update root authorization, does not need command, and may show a system authorization prompt."))
                        .put("session_id", text("Session ID returned by open. exec inherits the session's directory, environment, and identity; to change directory, run cd in the session.")),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_job",
                description = "Start a background command that keeps running after this reply ends. start returns job_id immediately and sends a system notification on completion or failure; the returned completion_notifications_enabled indicates whether notifications are currently available." +
                    "list lists tasks, read shows current status and logs, wait waits when a result is needed, and stop terminates a task." +
                    "Logs are saved to a file and read returns output_path; when the preview is truncated, use read_file to page through the full log." +
                    "Background commands use an independent shell and do not inherit the terminal session. After starting, you can continue other work or reply to the user without polling.",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "read", "wait", "stop"))
                        .put("command", text("The full command for start to run."))
                        .put("description", text("Optional short task description, used for listing and completion notifications."))
                        .put("timeout_ms", integer("Maximum run time for the start command, default 600000 milliseconds; 0 means no timeout."))
                        .put("job_id", text("Task ID returned by start or list; used by read, wait, and stop."))
                        .put("active_only", bool("Whether list returns only running tasks, default true."))
                        .put("max_bytes", integer("Number of trailing log bytes read returns, default 16000, at most 65536; the full log is saved at output_path."))
                        .put("wait_ms", integer("Maximum milliseconds wait blocks, default 30000, at most 60000; a wait timeout does not stop the command.")),
                    "action",
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_daemon",
                description = "Manage background services that must persist across Agent runs, such as a web service. start launches and returns task_id," +
                    "list lists tasks, logs reads logs, stop stops a task. Services are not automatically reclaimed when the current run ends," +
                    "but they may still terminate due to process exit, system reclamation, permission changes, or device reboot.",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "logs", "stop"))
                        .put("command", text("The command for start to run, using shell syntax supported by the actual environment."))
                        .put("task_id", text("Task ID returned by start or list; used by logs and stop.")),
                    "action",
                ),
            ),
        )
    }

    private fun executionProperties(): JSONObject = JSONObject()
        .put("environment", text("Execution environment (default android): use android to operate Android system/app/device state; explicitly pass linux for Git, writing code, running Python/Node scripts, jq/rg/curl, package management, and APK analysis. The workspace and shared directories map to the same physical folder on both sides; choose the environment by the nature of the command and use the corresponding path.")
            .put("enum", JSONArray(listOf("android", "linux"))))
        .put("identity", text("Optional host execution identity; defaults to root when Android root authorization is confirmed, otherwise user. When authorized, you need not try user first or re-detect; explicit user uses the Atom App UID. The simulated root inside Linux does not provide Android privileges.")
            .put("enum", JSONArray(listOf("user", "root"))))
        .put("cwd", text("Optional working directory; when omitted, the current environment's default workspace is used, and relative paths are also resolved against the default workspace."))

    private fun commandProperties(): JSONObject = executionProperties()
        .put("command", text("The command to run, using shell syntax supported by the actual environment."))
        .put("timeout_ms", integer("Command timeout in milliseconds, default 30000, at most 180000. A timeout terminates the command; a session command timeout also closes the session."))
        .put("merge_stderr", bool("Whether to merge stderr into stdout, default false."))

    private fun parameters(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))
        .put("additionalProperties", false)

    private fun action(vararg values: String): JSONObject = text("The action to perform.")
        .put("enum", JSONArray(values.toList()))

    private fun text(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
}
