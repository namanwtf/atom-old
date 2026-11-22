package io.github.namanwtf.atom.hook.system

import io.github.namanwtf.atom.config.PowerAssistantTarget
import io.github.namanwtf.atom.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
) {
    val shortComponentName: String
        get() = flattenToShortComponentName(componentName)
}

internal fun normalizeComponentName(component: String?): String? {
    val trimmed = component?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val slashIndex = trimmed.indexOf('/')
    if (slashIndex <= 0 || slashIndex >= trimmed.lastIndex) return null
    val packageName = trimmed.substring(0, slashIndex)
    val className = trimmed.substring(slashIndex + 1)
    if (packageName.isEmpty() || className.isEmpty()) return null
    val fullClassName = if (className.startsWith('.')) "$packageName$className" else className
    return "$packageName/$fullClassName"
}

internal fun flattenToShortComponentName(component: String): String {
    val normalized = normalizeComponentName(component) ?: return component
    val slashIndex = normalized.indexOf('/')
    val packageName = normalized.substring(0, slashIndex)
    val fullClassName = normalized.substring(slashIndex + 1)
    return if (fullClassName.startsWith("$packageName.")) {
        "$packageName/${fullClassName.removePrefix(packageName)}"
    } else {
        normalized
    }
}

internal fun isSameComponentName(left: String?, right: String?): Boolean {
    val normalizedLeft = normalizeComponentName(left) ?: return false
    val normalizedRight = normalizeComponentName(right) ?: return false
    return normalizedLeft == normalizedRight
}

internal fun extractPackageFromComponent(component: String?): String? =
    normalizeComponentName(component)?.substringBefore('/')

/**
 * When auto-takeover of the default assistant is enabled and the target is not the system default, intercept
 * HyperOS / Super Xiaoai's writes that flip `assistant` / `voice_interaction_service` back to Xiaoai, rewriting them to the current managed target component.
 */
internal fun rewriteManagedAssistantSettingIfOverwritten(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
    key: String?,
    value: String?,
): String? {
    if (!shouldConfigureAssistant(autoConfigEnabled, target)) return null
    if (key != ModuleConfig.SECURE_ASSISTANT &&
        key != ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE
    ) {
        return null
    }
    val binding = assistantBindingFor(target) ?: return null
    if (isSameComponentName(binding.componentName, value)) return null
    if (extractPackageFromComponent(value) != ModuleConfig.XIAOAI_PACKAGE) return null
    return binding.shortComponentName
}

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.OEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ATOM -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ATOM_PACKAGE,
        componentName = ModuleConfig.ATOM_VOICE_INTERACTION_COMPONENT,
        displayName = "Atom",
    )
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): Boolean = autoConfigEnabled && target != PowerAssistantTarget.OEM

internal enum class AssistantSelectionAction {
    NONE,
    CONFIGURE_MANAGED,
    RESTORE_OEM,
}

internal fun assistantSelectionAction(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): AssistantSelectionAction = when {
    target == PowerAssistantTarget.OEM -> AssistantSelectionAction.RESTORE_OEM
    shouldConfigureAssistant(autoConfigEnabled, target) ->
        AssistantSelectionAction.CONFIGURE_MANAGED
    else -> AssistantSelectionAction.NONE
}

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget) &&
    expectedTarget == currentTarget

/**
 * Serialize default-assistant switch tasks per user, and merge concurrent triggers into the latest pending resync request (latest-wins),
 * so that rapid successive switches between Atom / Gemini / system default do not lose the last change.
 */
internal class AssistantConfigurationCoordinator {
    private val lock = Any()
    private val inFlightUsers = mutableSetOf<Int>()
    private val pendingResyncUsers = mutableSetOf<Int>()

    fun tryBegin(userId: Int): Boolean = synchronized(lock) {
        if (userId in inFlightUsers) {
            pendingResyncUsers.add(userId)
            false
        } else {
            inFlightUsers.add(userId)
            true
        }
    }

    fun markPendingResync(userId: Int) {
        synchronized(lock) {
            pendingResyncUsers.add(userId)
        }
    }

    fun finish(userId: Int): Boolean = synchronized(lock) {
        inFlightUsers.remove(userId)
        pendingResyncUsers.remove(userId)
    }
}
