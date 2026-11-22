package io.github.namanwtf.atom.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    ATOM("atom"),
    GEMINI("gemini"),
    OEM("oem"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
            legacyPowerKeyTakeover: Boolean,
        ): PowerAssistantTarget = entries.firstOrNull {
            it.persistedValue == persistedValue
        } ?: if (legacyPowerKeyTakeover) {
            GEMINI
        } else {
            OEM
        }
    }
}
