package io.github.mangi.eta.ui.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.R

/**
 * Bundled monospace font. Some systems replace [FontFamily.Monospace] with a proportional font, breaking terminal column width and alignment;
 * the terminal UI uses this font uniformly, missing glyphs like CJK fall back to the system font, and grid alignment is guaranteed by cell-based drawing.
 * Uses the ligature-free version so `->` and `!=` aren't fused into single glyphs in commands and code.
 */
internal val EtaMonospace = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
