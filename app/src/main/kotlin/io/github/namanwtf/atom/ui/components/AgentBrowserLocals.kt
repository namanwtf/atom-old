package io.github.namanwtf.atom.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.namanwtf.atom.agent.browser.AgentBrowserSession

internal val LocalAgentBrowserSession = staticCompositionLocalOf<AgentBrowserSession?> { null }
