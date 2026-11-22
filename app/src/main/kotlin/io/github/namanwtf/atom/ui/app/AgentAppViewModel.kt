package io.github.namanwtf.atom.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Activity-level state owner; config changes only rebuild the UI without replacing the running Agent session. */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val skills = AgentSkillsStore(application, viewModelScope)
    val memory = AgentMemoryStore(application, viewModelScope)
    val permissionHealth = PermissionHealthStore(application, viewModelScope)
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
}
