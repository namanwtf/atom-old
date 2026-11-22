package io.github.namanwtf.atom.core

import java.io.File

/** The encrypted database must be read through a host connection, not treated as a corrupt or empty-table plain SQLite file. */
internal fun isPlaintextColorOsMemoryDatabase(file: File): Boolean = file.inputStream().use { input ->
    val signature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    input.readNBytes(signature.size).contentEquals(signature)
}
