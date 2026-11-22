package io.github.mangi.eta.agent.accessibility

/** When the main-thread query times out, UNKNOWN must not be treated as the window having disappeared. */
internal enum class PackageWindowVisibility {
    VISIBLE,
    GONE,
    UNKNOWN,
}
