package io.github.namanwtf.atom.agent.imagegen

import androidx.core.content.FileProvider

/** Dedicated FileProvider subclass, to avoid crashes on some devices or on library conflicts from registering the AndroidX base class directly. */
internal class AtomFileProvider : FileProvider()
