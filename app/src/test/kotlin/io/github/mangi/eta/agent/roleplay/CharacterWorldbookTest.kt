package io.github.mangi.eta.agent.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterWorldbookTest {
    @Test
    fun keywordsSecondaryKeysAndRecursionProduceOrderedUniqueEntries() {
        val card = card(""""recursive_scanning":true,"scan_depth":2,"entries":[
          {"id":1,"keys":["town"],"content":"lighthouse","enabled":true,"insertion_order":2},
          {"id":1,"keys":["lighthouse"],"content":"the town and the sea","enabled":true,"insertion_order":1,"position":"before_char"},
          {"keys":["town"],"secondary_keys":["night"],"selective":true,"content":"night scene","enabled":true,"insertion_order":3},
          {"constant":true,"content":"disabled","enabled":false},
          {"keys":["does not exist"],"content":"must not appear","enabled":true}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("I walk into the town", "night"), 1000) { it.length }
        assertEquals("the town and the sea", result.beforeCharacter)
        assertEquals("lighthouse\n\nnight scene", result.afterCharacter)
    }

    @Test
    fun disabledRecursionAndEmptySecondaryKeysDoNotActivateEntries() {
        val card = card(""""entries":[
          {"keys":["entry"],"content":"code word","enabled":true},
          {"keys":["code word"],"content":"hidden","enabled":true},
          {"keys":["entry"],"secondary_keys":[],"selective":true,"content":"empty condition","enabled":true}
        ]""")
        assertEquals("code word", CharacterWorldbook.resolve(card, listOf("entry"), 100) { it.length }.afterCharacter)
    }

    @Test
    fun budgetKeepsCompleteConstantEntriesAndHonorsExplicitZero() {
        val body = """"token_budget":6,"entries":[
          {"constant":true,"content":"constant","enabled":true,"insertion_order":9},
          {"keys":["x"],"content":"an overly long complete entry","enabled":true,"priority":100},
          {"keys":["x"],"content":"short sentence","enabled":true,"insertion_order":1}
        ]"""
        val result = CharacterWorldbook.resolve(card(body), listOf("x"), 100) { it.length }
        assertEquals("short sentence\n\nconstant", result.afterCharacter)
        assertFalse(result.afterCharacter.contains("too long"))
        assertEquals("", CharacterWorldbook.resolve(card(body.replace("\"token_budget\":6", "\"token_budget\":0")), listOf("x"), 100) { it.length }.afterCharacter)
    }

    @Test
    fun ordinaryWordsRemainLiteralEvenWithRegexFlagAndAliasesAreRead() {
        val card = card(""""entries":[
          {"keys":["A.B"],"use_regex":true,"content":"literal word","enabled":true,"extensions":{"position":0,"case_sensitive":true}},
          {"keys":["/hello/i"],"content":"expression","enabled":true},
          {"keys":["/[bad/"],"content":"invalid","enabled":true}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("A.B HELLO"), 100) { it.length }
        assertEquals("literal word", result.beforeCharacter)
        assertEquals("", result.afterCharacter)
        assertEquals(listOf(1, 2), CharacterWorldbook.unsupportedEntries(card).map { it.index })
        assertTrue(CharacterWorldbook.resolve(card, listOf("a.b"), 100) { it.length }.beforeCharacter.isEmpty())
    }

    @Test
    fun unsupportedConditionsPreventEvenConstantEntriesFromFiring() {
        val card = card(""""entries":[
          {"constant":true,"content":"special position","enabled":true,"extensions":{"position":4}},
          {"constant":true,"content":"conditional logic","enabled":true,"extensions":{"selectiveLogic":2}},
          {"constant":true,"content":"probability","enabled":true,"extensions":{"useProbability":true,"probability":30}},
          {"constant":true,"content":"group","enabled":true,"extensions":{"group":"some-group"}},
          {"constant":true,"content":"cooldown","enabled":true,"extensions":{"cooldown":2}},
          {"constant":true,"keys":["/(a+)+$/"],"content":"expression","enabled":true},
          {"constant":true,"content":"body","enabled":true,"extensions":{"position":1,"selectiveLogic":0,"probability":100,"group":"","cooldown":null}}
        ]""")
        val result = CharacterWorldbook.resolve(card, listOf("a".repeat(10_000) + "!"), 1000) { it.length }
        assertEquals("body", result.afterCharacter)
        assertEquals(6, CharacterWorldbook.unsupportedEntries(card).size)
    }

    private fun card(book: String) = CharacterCardCodec.decodeJson(
        """{"spec":"chara_card_v2","data":{"name":"character","character_book":{$book}}}""",
    )
}
