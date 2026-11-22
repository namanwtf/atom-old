package io.github.namanwtf.atom.agent.device

/** When the connection drops between coordinate conversion and the action, still return a recognizable accessibility status to the tool boundary. */
internal class DeviceControlUnavailableException : IllegalStateException(
    "Atom accessibility service disconnected; re-enable the service and observe the screen",
)
