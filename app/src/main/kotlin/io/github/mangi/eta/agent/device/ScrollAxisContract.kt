package io.github.mangi.eta.agent.device

/** When the other scroll axis is explicitly exposed, gesture fallback is forbidden so that a scroll is not mistaken for a list-item swipe. */
internal object ScrollAxisContract {
    fun exposesOnlyOppositeAxis(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean = when (requestedAxis) {
        ScrollAxis.VERTICAL -> hasHorizontalActions && !hasVerticalActions
        ScrollAxis.HORIZONTAL -> hasVerticalActions && !hasHorizontalActions
    }

    /** FORWARD/BACKWARD carry no axis semantics; treat them as vertical only when vertical is explicitly present and there is no horizontal evidence. */
    fun mayTreatLegacyActionsAsVertical(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean =
        requestedAxis == ScrollAxis.VERTICAL &&
            hasVerticalActions &&
            !hasHorizontalActions
}
