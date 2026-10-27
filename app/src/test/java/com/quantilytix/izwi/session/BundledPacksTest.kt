package com.quantilytix.izwi.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the packs that ship in assets/, read straight from src/main. */
class BundledPacksTest {

    private val assetDir = File("src/main/assets")

    private fun read(name: String) = ScriptJson.parse(File(assetDir, name).readText())

    @Test
    fun everyBundledPackParsesAndMatchesItsLanguage() {
        for (pack in ScriptStore.BUNDLED_PACKS) {
            val json = JSONObject(File(assetDir, pack.assetName!!).readText())
            assertEquals(pack.id, pack.language, json.getString("language"))
            assertTrue(pack.id, read(pack.assetName!!).prompts.isNotEmpty())
        }
    }

    @Test
    fun promptIdsAreUniqueAcrossEveryPackAndLanguage() {
        val byLanguage = ScriptStore.BUNDLED_PACKS.groupBy { it.language }
            .mapValues { (_, packs) -> packs.flatMap { p -> read(p.assetName!!).prompts.map { it.id } }.toSet() }
        for (pack in ScriptStore.BUNDLED_PACKS) {
            val ids = read(pack.assetName!!).prompts.map { it.id }
            assertEquals("duplicate ids in ${pack.id}", ids.size, ids.toSet().size)
        }
        val shona = byLanguage.getValue(Languages.SHONA)
        val ndebele = byLanguage.getValue(Languages.NDEBELE)
        assertTrue(shona.intersect(ndebele).isEmpty())
        assertTrue(ndebele.all { it.startsWith("nd_") })
        assertTrue(shona.none { it.startsWith("nd_") })
    }

    @Test
    fun ndebeleFreeFormIsTheShonaFreeFormWithNdebeleInstructions() {
        val sn = read("script_freeform_v1.json").prompts
        val nd = read("script_nd_freeform_v1.json").prompts
        assertEquals(sn.size, nd.size)
        for ((s, n) in sn.zip(nd)) {
            assertEquals("nd_${s.id}", n.id)
            assertEquals(s.category, n.category)
            assertFalse(n.id, n.text.contains("Shona"))
            if (s.category == "session_start") {
                assertTrue(n.text, n.text.contains("in Ndebele"))
                // The Shona match sentence is described in English, never copied.
                assertFalse(n.text.contains(s.text.lines().last()))
            } else {
                assertEquals(s.text.replace("Shona", "Ndebele"), n.text)
            }
        }
        assertEquals("nd-freeform-v1", JSONObject(File(assetDir, "script_nd_freeform_v1.json").readText()).getString("script_version"))
    }
}
