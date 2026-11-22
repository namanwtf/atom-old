package io.github.namanwtf.atom.agent.overlay

import android.view.View
import android.view.WindowManager

/**
 * Window helpers shared by both orb hosts ([AtomBubbleService] and the runtime
 * orb), so sizing and clamping can never drift between them.
 */

/** Grows or shrinks the orb window around a fixed orb position, then clamps. */
internal fun WindowManager.resizeOrbWindow(
    view: View,
    layout: WindowManager.LayoutParams,
    geometry: AtomOrbGeometry,
    expanded: Boolean,
) {
    val orbX = layout.x + layout.width - geometry.orbFromEdge
    val orbY = layout.y + layout.height - geometry.orbFromEdge
    layout.width = geometry.width(expanded)
    layout.height = geometry.height(expanded)
    layout.x = orbX - (layout.width - geometry.orbFromEdge)
    layout.y = orbY - (layout.height - geometry.orbFromEdge)
    val (x, y) = geometry.clamp(layout.x, layout.y, expanded)
    layout.x = x
    layout.y = y
    runCatching { updateViewLayout(view, layout) }
}

/** Drags the orb and keeps it inside the safe area. */
internal fun WindowManager.moveOrbWindow(
    view: View,
    layout: WindowManager.LayoutParams,
    geometry: AtomOrbGeometry,
    dx: Float,
    dy: Float,
    expanded: Boolean,
) {
    layout.x += dx.toInt()
    layout.y += dy.toInt()
    val (x, y) = geometry.clamp(layout.x, layout.y, expanded)
    layout.x = x
    layout.y = y
    runCatching { updateViewLayout(view, layout) }
}
