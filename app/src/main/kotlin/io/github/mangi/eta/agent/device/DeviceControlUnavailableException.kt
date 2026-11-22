package io.github.mangi.eta.agent.device

/** 在坐标换算与动作之间断连时，仍向工具边界返回可识别的无障碍状态。 */
internal class DeviceControlUnavailableException : IllegalStateException(
    "Eta accessibility service disconnected; re-enable the service and observe the screen",
)
