package io.github.namanwtf.atom.agent.roleplay

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardCodecTest {
    @Test
    fun legacyCardIsUpgradedAndUnknownValuesSurviveEditingAndPngRoundTrip() {
        val card = CharacterCardCodec.decodeJson("""{
          "name":"old name","description":"original setting","vendor":{"nested":[1,true,null]},
          "extensions":{"foreign":{"values":["a",{"x":2}]}}
        }""")
        val edited = card.withEdits(name = "new name", description = "new setting")
        val png = CharacterCardPng.write(avatar(), edited)
        val decoded = CharacterCardPng.read(png)
        assertEquals("new name", decoded.name)
        assertEquals("new setting", decoded.description)
        assertEquals(card.raw["vendor"], decoded.raw["vendor"])
        assertEquals(card.extensions, decoded.extensions)
        assertEquals("chara_card_v3", decoded.raw.text("spec"))
        assertTrue(png.toString(Charsets.ISO_8859_1).contains("chara"))
        assertTrue(png.toString(Charsets.ISO_8859_1).contains("ccv3"))
        val second = CharacterCardPng.read(CharacterCardPng.write(png, decoded.withEdits(name = "re-edit")))
        assertEquals("re-edit", second.name)
    }

    @Test
    fun versionedDataWinsOverStaleLegacyMirrors() {
        val card = CharacterCardCodec.decodeJson("""{
          "spec":"chara_card_v3","spec_version":"3.0","name":"stale mirror",
          "data":{"name":"current name","nickname":"nickname","assets":[{"uri":"x","type":"other"}],"extensions":{}}
        }""")
        assertEquals("current name", card.name)
        val roundTrip = CharacterCardCodec.decodeJson(CharacterCardCodec.exportView(card, 3))
        assertEquals(card.data["assets"], roundTrip.data["assets"])
        assertEquals("current name", roundTrip.raw.text("name"))
        assertEquals("nickname", roundTrip.nickname)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ordinaryPngIsNotSilentlyImportedAsCharacter() { CharacterCardPng.read(avatar()) }

    @Test(expected = IllegalArgumentException::class)
    fun corruptPngIsRejected() {
        val bytes = CharacterCardPng.write(avatar(), CharacterCardCodec.create("character"))
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        CharacterCardPng.read(bytes)
    }

    @Test
    fun macrosExpandKnownFieldsWithoutExecutingUnknownVariables() {
        val card = CharacterCardCodec.create("Lin").withEdits(description = "{{char}}lives in the mountains.")
        val result = CharacterMacros.expand("{{description}} <USER> {{original}} {{setvar::x::1}}", card, "Xiao'an", original = "original instruction")
        assertEquals("Lin lives in the mountains. Xiao'an original instruction {{setvar::x::1}}", result)
        assertEquals("{{description}}", CharacterMacros.expand("{{description}}", card.withEdits(description = "{{description}}")))
        assertFalse(CharacterMacros.expand("{{//comment}}hello", card).contains("comment"))
    }

    @Test
    fun editingWorldbookPreservesForeignBookAndEntryExtensions() {
        val card = CharacterCardCodec.decodeJson("""{"name":"character","character_book":{
          "name":"world","vendor":"keep","extensions":{"vendor":{"x":[1,2]}},
          "entries":[{"id":7,"keys":["mountain"],"content":"old body","enabled":true,"insertion_order":3,
            "extensions":{"position":0,"unknown":["a",{"b":true}]}}]
        }}""")
        val draft = card.worldbookDraft()
        val updated = card.withWorldbook(draft.copy(name = "New World", scanDepth = 4,
            entries = draft.entries.map { it.copy(content = "new body", position = "after_char") },
        ))
        val result = CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(updated)).worldbookDraft()
        assertEquals("new body", result.entries.single().content)
        assertEquals("after_char", result.entries.single().position)
        assertEquals(draft.raw["vendor"], result.raw["vendor"])
        assertEquals(draft.raw["extensions"], result.raw["extensions"])
        assertEquals(draft.entries.single().raw["id"], result.entries.single().raw["id"])
        assertEquals(4, result.scanDepth)
    }

    @Test
    fun compatibilityWarningsShareMacroAndWorldbookSupportWithoutEchoingCardText() {
        val card = CharacterCardCodec.decodeJson("""{"name":"character",
          "description":"{{setvar::private_key::private_value}} <script>private_script()</script>",
          "extensions":{"regex_scripts":[{"x":"private_regex"}],"scripts":["private_script"],
            "depth_prompt":{"prompt":"note","depth":"invalid"}},
          "character_book":{"entries":[{"enabled":true,"constant":true,"content":"body","extensions":{"group":"private_group"}}]}
        }""")
        val warnings = CharacterCardCompatibility.warnings(card)
        assertTrue(warnings.any { it.contains("unsupported macro") })
        assertTrue(warnings.any { it.contains("HTML") })
        assertTrue(warnings.any { it.contains("regex replacement") })
        assertTrue(warnings.any { it.contains("1 worldbook entry") })
        assertTrue(warnings.any { it.contains("depth note") })
        assertFalse(warnings.any { it.contains("private_") })
        assertEquals(null, card.depthPrompt)
        assertEquals(emptyList<String>(), CharacterCardCompatibility.warnings(CharacterCardCodec.create("character")
            .withEdits(description = "{{char}} and {{user}} converse.<START> {{original}}")))
    }

    @Test
    fun unrelatedWorldbookEditPreservesUnsupportedPosition() {
        val card = CharacterCardCodec.decodeJson("""{"name":"character","character_book":{"entries":[
          {"enabled":true,"constant":true,"content":"body","position":"at_depth","extensions":{"position":4,"depth":2}}
        ]}}""")
        val updated = card.withWorldbook(card.worldbookDraft().copy(name = "new book title"))
        assertEquals("at_depth", updated.worldbookDraft().entries.single().position)
        assertEquals(1, CharacterWorldbook.unsupportedEntries(updated).size)
    }

    @Test
    fun invalidJsonAndMissingMetadataHaveStableErrorCodes() {
        val failures = listOf(
            "CARD_INVALID_JSON" to { CharacterCardCodec.decodeJson("not-json") },
            "CARD_METADATA_MISSING" to { CharacterCardPng.read(avatar()) },
        )
        failures.forEach { (expected, operation) ->
            try {
                operation()
                throw AssertionError("Expected character card failure")
            } catch (failure: CharacterCardException) {
                assertEquals(expected, failure.code)
            }
        }
    }

    @Test
    fun changingOneVersionThreeFieldDoesNotMaterializeOtherMissingFields() {
        val card = CharacterCardCodec.decodeJson("""{"spec":"chara_card_v3","spec_version":"3.0",
          "foreign":{"x":1},"data":{"name":"original name","description":"description","extensions":{"vendor":[1,true]}}}""")
        val updated = card.withEdits(name = "new name")
        val restored = CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(updated))
        assertEquals(card.data.filterKeys { it != "name" }, restored.data.filterKeys { it != "name" })
        assertEquals(card.data.keys, restored.data.keys)
        assertEquals(card.raw["foreign"], restored.raw["foreign"])
        assertEquals("new name", restored.name)
        assertFalse(restored.data.containsKey("alternate_greetings"))
        assertFalse(restored.data.containsKey("creator_notes"))
    }

    @Test
    fun amplifiedCrossFieldMacrosFailBeforeBuildingUnboundedOutput() {
        val card = CharacterCardCodec.create("character").withEdits(
            description = "{{scenario}}".repeat(100),
            scenario = "{{personality}}".repeat(100),
            personality = "setting".repeat(200),
        )
        try {
            CharacterMacros.expand("{{description}}", card)
            throw AssertionError("Expected bounded expansion failure")
        } catch (failure: CharacterCardException) {
            assertEquals("CARD_MACRO_EXPANSION_LIMIT", failure.code)
        }
    }

    private fun avatar() = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
    )
}
