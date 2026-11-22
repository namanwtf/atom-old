package io.github.namanwtf.atom.hook.hyperos

import android.content.Context
import io.github.namanwtf.atom.config.Prefs
import io.github.namanwtf.atom.core.safeLogType
import io.github.namanwtf.atom.core.ModuleLogger
import io.github.namanwtf.atom.hook.system.CircleToSearchInvoker

internal object HyperOsSearchTrigger {
    fun isEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)

    fun trigger(context: Context?, logger: ModuleLogger): Boolean {
        if (!isEnabled()) return false
        if (context == null) {
            logger.warnThrottled("hyperos_search_context_missing") { "HyperOS: search entry lacks Context, keeping system behavior" }
            return false
        }
        return try {
            CircleToSearchInvoker.isAvailable(context, logger, "HyperOS", "keep system behavior") &&
                CircleToSearchInvoker.trigger(logger, "HyperOS", entryPoint = 1)
        } catch (exception: Exception) {
            logger.warnThrottled("hyperos_search_failed") {
                "HyperOS: search trigger failed, type=${exception.safeLogType()}"
            }
            false
        }
    }
}
