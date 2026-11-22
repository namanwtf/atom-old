package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings

/** Import exposes only a controlled category; file content and underlying exception text never reach the UI. */
internal fun characterCardImportMessage(code: String?): String? = when (code) {
    "CARD_V3_INVALID" -> AppStrings.get(
        R.string.character_import_error_v3_invalid,
        "The preferred V3 character data in the PNG is corrupted and did not fall back to V2. Please re-download the original character card.",
    )
    "CARD_METADATA_MISSING" -> AppStrings.get(
        R.string.character_import_error_metadata_missing,
        "This PNG has no character card data. Please select the original character card image, not a screenshot or compressed image.",
    )
    "CARD_INVALID_JSON" -> AppStrings.get(
        R.string.character_import_error_invalid_json,
        "Character card JSON format is invalid. Please check whether the file is complete.",
    )
    "CARD_TOO_LARGE" -> AppStrings.get(
        R.string.character_import_error_too_large,
        "The character card exceeds the size limit. Please simplify the settings or choose a smaller file.",
    )
    "CARD_INVALID_UTF8" -> AppStrings.get(
        R.string.character_import_error_invalid_utf8,
        "Character card text encoding is invalid. A UTF-8 encoded JSON file is required.",
    )
    "CARD_UNSUPPORTED_SPEC" -> AppStrings.get(
        R.string.character_import_error_unsupported_spec,
        "The format declared by this character card is not supported yet. Please export as a V2 or V3 character card and retry.",
    )
    "CARD_INVALID_PNG" -> AppStrings.get(
        R.string.character_import_error_invalid_png,
        "The PNG file is corrupted or incomplete. Please re-download the original character card.",
    )
    "CARD_INVALID_DATA" -> AppStrings.get(
        R.string.character_import_error_invalid_data,
        "Character card field format is invalid. Please check the name, text, greeting, and embedded worldbook data.",
    )
    else -> null
}
