package io.github.namanwtf.atom.data.model

import kotlinx.serialization.Serializable

/**
 * Custom HTTP headers at the provider or model level.
 *
 * Note: dangerous headers such as host / content-length / connection / transfer-encoding
 * are filtered at the network layer to prevent breaking the HTTP protocol.
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)
