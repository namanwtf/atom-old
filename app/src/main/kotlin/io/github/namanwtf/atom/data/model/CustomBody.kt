package io.github.namanwtf.atom.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Custom request body fields at the provider or model level.
 *
 * The network layer recursively merges them into the final request JSON, with the model level overriding the provider level.
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
