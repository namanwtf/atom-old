package io.github.mangi.eta.ui.app

import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

internal fun ComponentActivity.installStartupSplash(
    isContentReady: () -> Boolean,
) {
    StartupSplash(this, isContentReady).install()
}

private class StartupSplash(
    private val activity: ComponentActivity,
    private val isContentReady: () -> Boolean,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private val content = activity.findViewById<View>(android.R.id.content)

    fun install() {
        activity.lifecycle.addObserver(this)
        // Allow the first frame only after appearance config finishes loading asynchronously, so the system splash doesn't exit into a blank window.
        content.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (!isContentReady()) return false
        content.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
        }
        activity.lifecycle.removeObserver(this)
    }
}
