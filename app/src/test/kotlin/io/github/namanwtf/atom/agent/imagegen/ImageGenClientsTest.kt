package io.github.namanwtf.atom.agent.imagegen

import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.data.model.CustomHeader
import io.github.namanwtf.atom.data.model.GeminiEndpointMode
import io.github.namanwtf.atom.data.model.ImageGenAspectRatio
import io.github.namanwtf.atom.data.model.ImageGenBackend
import io.github.namanwtf.atom.data.model.ImageGenCredentials
import io.github.namanwtf.atom.data.model.ImageGenModelPresets
import io.github.namanwtf.atom.data.model.ImageGenResolution
import io.github.namanwtf.atom.data.model.ImageGenSettings
import io.github.namanwtf.atom.data.model.OpenAiCompatibleProviderSetting
import io.github.namanwtf.atom.data.model.ProviderSourceTypes
import io.github.namanwtf.atom.data.model.QwenImageConfig
import io.github.namanwtf.atom.data.model.SpeechRegion
import io.github.namanwtf.atom.data.provider.BuiltinProviders
import io.github.namanwtf.atom.data.repository.ImageGenSettingsRepository
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenClientsTest {

    private val samplePngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )
    private val samplePngBase64 = Base64.getEncoder().encodeToString(samplePngBytes)
    private val sampleJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46,
    )
    private val sampleJpegBase64 = Base64.getEncoder().encodeToString(sampleJpegBytes)

    @Test
    fun geminiInteractionsRestStepsParsingExtractsFinalImagesOnlyAndSetsStoreFalse() {
        val config = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.GEMINI,
            model = "gemini-nano-banana-2.1",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test-key",
        )
        val req = ImageGenRequest(
            prompt = "A cozy rainy cyberpunk cafe",
            aspectRatio = ImageGenAspectRatio.RATIO_16_9,
            resolution = ImageGenResolution.R_2K,
            referenceImages = listOf(ImageGenInputImage(samplePngBytes, "image/png")),
        )
        val body = ImageGenClient.buildGeminiInteractionsBody(config, req)
        assertEquals("gemini-nano-banana-2.1", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        val format = body.getJSONObject("response_format")
        assertEquals("image", format.getString("type"))
        assertFalse(format.has("mime_type"))
        assertEquals("16:9", format.getString("aspect_ratio"))
        assertEquals("2K", format.getString("image_size"))

        // Official REST response: the finished image is inside steps[].model_output.content[], mixed with thought steps and text.
        val restResponseJson = JSONObject()
            .put("id", "int_rest_1")
            .put(
                "steps",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "thought")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "image")
                                        .put("mime_type", "image/jpeg")
                                        .put("data", sampleJpegBase64),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "model_output")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", "Here are your images."))
                                    .put(
                                        JSONObject()
                                            .put("type", "image")
                                            .put("thought", true)
                                            .put("mime_type", "image/jpeg")
                                            .put("data", sampleJpegBase64),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "image")
                                            .put("mime_type", "image/png")
                                            .put("data", samplePngBase64),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "image")
                                            .put("mime_type", "image/jpeg")
                                            .put("data", sampleJpegBase64),
                                    ),
                            ),
                    ),
            )
            .toString()

        val parsed = ImageGenClient.parseGeminiInteractionsResponse(restResponseJson)
        assertEquals(2, parsed.size)
        assertEquals("image/png", parsed[0].mimeType)
        assertEquals("image/jpeg", parsed[1].mimeType)

        // The compatible proxy outputs[] branch likewise filters thought images.
        val proxyOutputsJson = JSONObject()
            .put(
                "outputs",
                JSONArray()
                    .put(JSONObject().put("type", "image").put("thought", true).put("data", sampleJpegBase64))
                    .put(JSONObject().put("type", "image").put("data", samplePngBase64)),
            )
            .toString()
        val parsedOutputs = ImageGenClient.parseGeminiInteractionsResponse(proxyOutputsJson)
        assertEquals(1, parsedOutputs.size)
        assertEquals("image/png", parsedOutputs[0].mimeType)
    }

    @Test
    fun geminiModelCapabilitiesClampLiteTo1KAndOmitImageSizeFor25() {
        val liteConfig = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.GEMINI,
            model = "gemini-3.1-flash-lite-image",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test-key",
            defaultResolution = ImageGenResolution.R_4K,
        )
        assertEquals(listOf(ImageGenResolution.R_1K), liteConfig.capability.supportedResolutions)
        assertEquals(ImageGenResolution.R_1K, liteConfig.effectiveDefaultResolution)
        val liteBody = ImageGenClient.buildGeminiInteractionsBody(
            liteConfig,
            ImageGenRequest(prompt = "cat", resolution = ImageGenResolution.R_4K),
        )
        assertEquals("1K", liteBody.getJSONObject("response_format").getString("image_size"))

        val legacy25Config = liteConfig.copy(
            model = "gemini-2.5-flash-image",
            geminiEndpointMode = GeminiEndpointMode.VERTEX_AI,
            baseUrl = "https://us-central1-aiplatform.googleapis.com/v1beta1",
        )
        assertFalse(legacy25Config.capability.sendsResolutionParam)
        val legacyAiStudio = ImageGenClient.buildGeminiInteractionsBody(
            legacy25Config,
            ImageGenRequest(prompt = "cat", resolution = ImageGenResolution.R_2K),
        )
        assertFalse(legacyAiStudio.getJSONObject("response_format").has("image_size"))
        val legacyVertex = ImageGenClient.buildGeminiVertexBody(
            legacy25Config,
            ImageGenRequest(prompt = "cat", resolution = ImageGenResolution.R_2K),
        )
        assertFalse(legacyVertex.getJSONObject("generationConfig").has("imageConfig"))

        // Vertex Express (no Project) normalizes regional official addresses to the global host.
        val expressUrl = ImageGenClient.resolveVertexImageUrl(legacy25Config)
        assertEquals(
            "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/gemini-2.5-flash-image:generateContent",
            expressUrl,
        )
    }

    @Test
    fun openAiPresetsExcludeRetiredGptImage1AndEditsIncludeQuality() {
        assertFalse(ImageGenModelPresets.OPENAI_COMPATIBLE.any { it.id == "gpt-image-1" })
        val retiredCap = ImageGenModelCapability.resolve(
            ImageGenBackend.OPENAI_COMPATIBLE,
            "gpt-image-1",
            "https://api.openai.com/v1",
        )
        assertNotNull(retiredCap.deprecationNotice)
        val relayCap = ImageGenModelCapability.resolve(
            ImageGenBackend.OPENAI_COMPATIBLE,
            "gpt-image-1",
            "https://relay.example.com/v1",
        )
        assertNull(relayCap.deprecationNotice)

        val gptConfig = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.OPENAI_COMPATIBLE,
            model = "gpt-image-2.6-flare",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
        )
        val editMultipart = ImageGenClient.buildOpenAiEditsMultipart(
            config = gptConfig,
            request = ImageGenRequest(
                prompt = "Add sunglasses",
                aspectRatio = ImageGenAspectRatio.RATIO_16_9,
                resolution = ImageGenResolution.R_2K,
                referenceImages = listOf(ImageGenInputImage(samplePngBytes, "image/png")),
            ),
        )
        val partHeaders = editMultipart.parts.mapNotNull { it.headers?.get("Content-Disposition") }
        assertTrue(partHeaders.any { it.contains("name=\"quality\"") })
        assertTrue(partHeaders.any { it.contains("name=\"image\"") })
    }

    @Test
    fun xaiAndSiliconFlowHaveDedicatedProtocolsAndParsers() {
        val xaiConfig = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.XAI,
            model = "grok-imagine-image-2.0",
            baseUrl = "https://api.x.ai/v1",
            apiKey = "xai-key",
        )
        val xaiGen = ImageGenClient.buildXaiGenerationsBody(
            xaiConfig,
            ImageGenRequest(
                prompt = "Neon street",
                aspectRatio = ImageGenAspectRatio.RATIO_16_9,
                resolution = ImageGenResolution.R_2K,
            ),
        )
        assertEquals("2k", xaiGen.getString("resolution"))
        assertEquals("16:9", xaiGen.getString("aspect_ratio"))

        val xaiEdit = ImageGenClient.buildXaiEditsBody(
            xaiConfig,
            ImageGenRequest(
                prompt = "Sketch",
                resolution = ImageGenResolution.R_2K,
                referenceImages = listOf(
                    ImageGenInputImage(samplePngBytes, "image/png"),
                    ImageGenInputImage(sampleJpegBytes, "image/jpeg"),
                ),
            ),
        )
        assertEquals("2k", xaiEdit.getString("resolution"))
        assertEquals(2, xaiEdit.getJSONArray("images").length())

        val sfConfig = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.SILICONFLOW,
            model = "Qwen/Qwen-Image",
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sf-key",
        )
        val sfBody = ImageGenClient.buildSiliconFlowBody(
            sfConfig,
            ImageGenRequest(
                prompt = "Chinese garden",
                aspectRatio = ImageGenAspectRatio.RATIO_16_9,
                resolution = ImageGenResolution.R_1K,
            ),
        )
        assertEquals("1280x720", sfBody.getString("image_size"))
        assertFalse(sfBody.has("batch_size"))
        assertFalse(sfBody.has("size"))

        // FLUX.1-schnell only supports the 1K tier and for 16:9 must send the official enum 1024x576.
        val schnellConfig = sfConfig.copy(
            model = "black-forest-labs/FLUX.1-schnell",
            defaultResolution = ImageGenResolution.R_2K,
        )
        assertEquals(listOf(ImageGenResolution.R_1K), schnellConfig.capability.supportedResolutions)
        assertEquals(ImageGenResolution.R_1K, schnellConfig.effectiveDefaultResolution)
        val schnellBody = ImageGenClient.buildSiliconFlowBody(
            schnellConfig,
            ImageGenRequest(
                prompt = "Mountain",
                aspectRatio = ImageGenAspectRatio.RATIO_16_9,
                resolution = ImageGenResolution.R_2K,
            ),
        )
        assertEquals("1024x576", schnellBody.getString("image_size"))
        val schnellAuto = ImageGenClient.buildSiliconFlowBody(
            schnellConfig,
            ImageGenRequest(
                prompt = "Mountain",
                aspectRatio = ImageGenAspectRatio.AUTO,
                resolution = ImageGenResolution.R_2K,
            ),
        )
        assertEquals("1024x1024", schnellAuto.getString("image_size"))

        // Verify that the SiliconFlow images[].url response downloads and parses correctly.
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(samplePngBytes.toResponseBody())
                    .build()
            }
            .build()
        val sfResp = """{"images":[{"url":"https://sf-cdn.example.com/out.png"}]}"""
        val sfParsed = ImageGenClient.parseOpenAiImageResponse(sfResp, null, fakeClient)
        assertEquals(1, sfParsed.size)
        assertEquals("image/png", sfParsed[0].mimeType)
    }

    @Test
    fun doubaoOfficialModelIdsAndCustomEndpointPreservation() {
        val ids = ImageGenModelPresets.DOUBAO.map { it.id }
        assertTrue("doubao-seedream-5-5-261028" in ids)
        assertTrue("doubao-seedream-5-0-pro-260628" in ids)
        assertTrue("doubao-seedream-5-0-flash-260915" in ids)
        assertTrue("doubao-seedream-5-0-lite-260128" in ids)

        // Unknown custom Endpoint IDs are not hard-downgraded; 4K is allowed through.
        val customEpSize = ImageGenSizeMapper.doubaoSize(
            aspectRatio = ImageGenAspectRatio.AUTO,
            resolution = ImageGenResolution.R_4K,
            model = "ep-20261119-custom",
        )
        assertEquals("4K", customEpSize)
    }

    @Test
    fun credentialsAndBaseUrlStayStrictlyBoundAndEnforceHttps() {
        val beijingBailian = OpenAiCompatibleProviderSetting(
            id = BuiltinProviders.BAILIAN_ID,
            name = "Alibaba Bailian",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
            apiKey = "sk-beijing",
            customHeaders = listOf(CustomHeader("X-Tenant", "bj")),
        )
        // User picks Singapore and fills a separate Singapore Key: must request the Singapore domain and not send the Beijing provider Header.
        val standaloneSg = ImageGenSettingsRepository.resolveRuntimeConfigPure(
            settings = ImageGenSettings(
                enabled = true,
                backend = ImageGenBackend.QWEN,
                qwen = QwenImageConfig(
                    linkedProviderId = "",
                    region = SpeechRegion.SINGAPORE,
                ),
            ),
            credentials = ImageGenCredentials(qwenApiKey = "sk-singapore"),
            allProviders = listOf(beijingBailian),
        )
        assertEquals("https://dashscope-intl.aliyuncs.com", standaloneSg.baseUrl)
        assertEquals("sk-singapore", standaloneSg.apiKey)
        assertTrue(standaloneSg.customHeaders.isEmpty())

        // Associated mode: even if the standalone fields hold another URL, fully use the associated provider's address, Key and Header, never sending the Key across hosts.
        val linkedBj = ImageGenSettingsRepository.resolveRuntimeConfigPure(
            settings = ImageGenSettings(
                enabled = true,
                backend = ImageGenBackend.QWEN,
                qwen = QwenImageConfig(
                    linkedProviderId = BuiltinProviders.BAILIAN_ID,
                    region = SpeechRegion.SINGAPORE,
                    baseUrl = "https://evil.example.com",
                ),
            ),
            credentials = ImageGenCredentials(),
            allProviders = listOf(beijingBailian),
        )
        assertEquals("https://dashscope.aliyuncs.com", linkedBj.baseUrl)
        assertEquals("sk-beijing", linkedBj.apiKey)
        assertEquals(1, linkedBj.customHeaders.size)

        // Public HTTP is blocked; HTTPS and localhost HTTP are allowed.
        assertNotNull(ImageGenSettingsRepository.validateEndpointUrl("http://api.openai.com/v1"))
        assertNull(ImageGenSettingsRepository.validateEndpointUrl("https://api.openai.com/v1"))
        assertNull(ImageGenSettingsRepository.validateEndpointUrl("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun safeHttpErrorRedactsSecretsAndPreservesDashScopeCode() {
        val (code, msg) = ImageGenClient.buildSafeHttpError(
            label = "Qwen Qwen-Image",
            httpCode = 401,
            rawBody = """{"code":"InvalidApiKey","message":"Bearer sk-secret1234567890 echoed prompt secret","request_id":"req-abc-123"}""",
            isDashScope = true,
        )
        assertEquals("QWEN_InvalidApiKey", code)
        assertTrue(msg.contains("InvalidApiKey"))
        assertTrue(msg.contains("req-abc-123"))
        assertFalse(msg.contains("sk-secret1234567890"))
        assertFalse(msg.contains("echoed prompt secret"))
    }

    @Test
    fun authClientBlocksRedirectsAndQwenEditPreservesPortraitAspectRatioAt2K() {
        assertFalse(ImageGenClient.authHttpClient.followRedirects)
        assertFalse(ImageGenClient.authHttpClient.followSslRedirects)
        assertFalse(ImageGenClient.downloadHttpClient.followSslRedirects)

        // Auth requests hitting a 302 redirect are blocked outright, preventing cross-origin leakage of x-goog-api-key or the POST body.
        val redirectClient = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://attacker.example.com/steal")
                    .body("".toResponseBody())
                    .build()
            }
            .build()
        val geminiCfg = ImageGenRuntimeConfig(
            enabled = true,
            backend = ImageGenBackend.GEMINI,
            model = "gemini-nano-banana-2.1",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIza-test",
        )
        val err = runCatching {
            ImageGenClient.generate(geminiCfg, ImageGenRequest(prompt = "cat"), httpClient = redirectClient)
        }.exceptionOrNull() as? ImageGenException
        assertNotNull(err)
        assertEquals("HTTP_302_REDIRECT_BLOCKED", err!!.code)

        // Qwen portrait edit keeps the portrait ratio under AUTO + 2K, no longer squashing into a 2048*2048 square.
        val qwenPortraitSize = ImageGenSizeMapper.qwenSize(
            aspectRatio = ImageGenAspectRatio.AUTO,
            resolution = ImageGenResolution.R_2K,
            isEdit = true,
            referenceAspectRatio = 0.75f,
        )
        assertNotNull(qwenPortraitSize)
        val parts = qwenPortraitSize!!.split("*").map(String::toInt)
        assertTrue(parts[0] < parts[1])
        assertTrue(parts[0] % 16 == 0 && parts[1] % 16 == 0)
        assertTrue(parts[0].toLong() * parts[1].toLong() <= 2048L * 2048L)
    }

    @Test
    fun transcriptSanitizesInlineDataAndLocalPathInGenerateImageArgs() {
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "assistant")
                .put("content", "")
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "call_1")
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", "generate_image")
                                    .put(
                                        "arguments",
                                        JSONObject()
                                            .put("prompt", "edit")
                                            .put(
                                                "reference_images",
                                                JSONArray()
                                                    .put("data:image/png;base64,iVBORw0KGgo=")
                                                    .put("/data/user/0/io.github.namanwtf.atom/files/secret.png")
                                                    .put("latest_generated_image"),
                                            )
                                            .toString(),
                                    ),
                            ),
                    ),
                ),
        )
        val transcript = AgentConversationCodec.transcript(messages, startIndex = 0)
        assertEquals(1, transcript.size)
        val savedArgs = JSONArray(transcript[0].toolCallsJson)
            .getJSONObject(0)
            .getJSONObject("function")
            .getString("arguments")
        assertFalse(savedArgs.contains("iVBORw0KGgo"))
        assertFalse(savedArgs.contains("secret.png"))
        assertTrue(savedArgs.contains("<redacted_inline_image>"))
        assertTrue(savedArgs.contains("<redacted_local_path>"))
        assertTrue(savedArgs.contains("latest_generated_image"))
    }

    @Test
    fun payloadRoundTripAndToolCatalogSchema() {
        val initial = GeneratedImageActivityPayload.initialFromArguments(
            """{"prompt":"a Shiba Inu","aspect_ratio":"16:9","count":2,"reference_images":["latest_user_image"]}""",
        )
        assertNotNull(initial)
        assertEquals("a Shiba Inu", initial!!.prompt)
        assertEquals("16:9", initial.aspectRatio)
        assertEquals(2, initial.requestedCount)
        assertEquals(1, initial.referenceCount)

        val completed = initial.copy(
            model = "gemini-nano-banana-2.1",
            backend = "gemini",
            images = listOf(
                GeneratedImageActivityPayload.GeneratedImageItem(
                    id = "img_1",
                    filePath = "/data/user/0/io.github.namanwtf.atom/files/generated_images/img_1.png",
                    width = 1920,
                    height = 1080,
                ),
            ),
        )
        val decoded = GeneratedImageActivityPayload.decode(completed.encode())
        assertEquals(completed, decoded)

        val tools = JSONArray().also(ImageGenToolCatalog::appendTo)
        assertEquals(1, tools.length())
        val fn = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("generate_image", fn.getString("name"))
        val req = fn.getJSONObject("parameters").getJSONArray("required")
        assertEquals("prompt", req.getString(0))
    }
}
