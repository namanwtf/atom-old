package io.github.mangi.eta.agent.imagegen

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 生图与图像编辑统一工具 Schema。
 *
 * 仅在用户在「设置 → AI 生图」启用后注入模型工具列表，避免未配置时占用上下文 Token。
 */
internal object ImageGenToolCatalog {
    const val TOOL_NAME = "generate_image"

    val names: Set<String> = setOf(TOOL_NAME)

    fun appendTo(target: JSONArray) {
        val properties = JSONObject()
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "A detailed image generation or editing prompt describing the subject, composition, style, lighting, mood, and any text that must be rendered accurately in the image.",
                    ),
            )
            .put(
                "aspect_ratio",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")))
                    .put("description", "The image aspect ratio; pass \"auto\" when not explicitly specified to follow the user's default setting."),
            )
            .put(
                "resolution",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("1K", "2K", "4K")))
                    .put("description", "Resolution tier (optional); omit it when high resolution is not explicitly required to follow the user's default configuration."),
            )
            .put(
                "count",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 4)
                    .put("description", "Number of images to generate; default 1, maximum 4."),
            )
            .put(
                "reference_images",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put(
                        "description",
                        "List of images for image-to-image, local editing, or style reference (optional). Pass \"latest_user_image\" (references the user's most recently sent image), \"latest_generated_image\" (references the most recently generated image in this session), a generated image ID (img_...), or an absolute local image path.",
                    ),
            )
        target.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "Use the user's configured AI image generation model to generate new images from a prompt, or perform image editing and style transfer based on reference images. Generated images are shown directly in the chat interface; do not fabricate image links in the reply.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(listOf("prompt"))),
            ),
        )
    }
}
