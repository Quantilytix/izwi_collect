package com.quantilytix.izwi.session

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun script(version: String, language: String, vararg prompts: Pair<String, String>): String {
        val items = prompts.joinToString(",") { (id, text) ->
            """{"id":"$id","category":"${id.substringBeforeLast('_')}","text":"$text"}"""
        }
        return """{"script_version":"$version","language":"$language","note":"fixture","prompts":[$items]}"""
    }

    // Stand-ins for the three bundled packs. Placeholder English only.
    private val assets = mapOf(
        "script_v1.json" to script("v2-draft", "sn", "names_0001" to "Read one", "names_0002" to "Read two", "stock_0001" to "Read three"),
        "script_freeform_v1.json" to script("freeform-v1", "sn", "free_form_cue_0001" to "Cue one", "own_words_0001" to "Topic one"),
        "script_nd_freeform_v1.json" to script("nd-freeform-v1", "nd", "nd_free_form_cue_0001" to "Cue one", "nd_own_words_0001" to "Topic one"),
    )

    private val adopted = mutableListOf<String>()

    private fun store(root: File = tmp.root) = ScriptStore(root, { assets.getValue(it) }, { adopted.add(it) })

    private val now = "2026-10-27T10:00:00Z"

    @Test
    fun freshInstallUsesTheReadScriptForShonaAndFreeFormForNdebele() {
        val s = store()
        assertEquals("sn_read_v1", s.load(Languages.SHONA).pack.id)
        assertEquals("v2-draft", s.load(Languages.SHONA).scriptVersion)
        assertEquals("nd_freeform_v1", s.load(Languages.NDEBELE).pack.id)
        assertTrue(s.load(Languages.SHONA).prompts.all { it.version == 1 && it.editedAtUtc == null })
        assertEquals(listOf("sn_read_v1"), adopted)
    }

    @Test
    fun editKeepsIdBumpsVersionAndStampsTime() {
        val s = store()
        val edited = s.editText(Languages.SHONA, "names_0002", "  Read two, fixed  ", now)!!
        assertEquals("names_0002", edited.id)
        assertEquals("Read two, fixed", edited.text)
        assertEquals(2, edited.version)
        assertEquals(now, edited.editedAtUtc)

        // Saved immediately: a new store over the same folder reads it back.
        val reloaded = store().load(Languages.SHONA)
        assertEquals(edited, reloaded.prompts.first { it.id == "names_0002" })
        assertTrue(reloaded.edited)
        assertEquals(1, reloaded.prompts.first { it.id == "names_0001" }.version)
        assertEquals("v2-draft", reloaded.scriptVersion)

        // Stored in the script JSON as "version" and "edited_at".
        val json = JSONObject(File(tmp.root, "sn/sn_read_v1.json").readText())
        val obj = json.getJSONArray("prompts").getJSONObject(1)
        assertEquals(2, obj.getInt("version"))
        assertEquals(now, obj.getString("edited_at"))
        assertEquals("fixture", json.getString("note"))

        assertEquals(3, s.editText(Languages.SHONA, "names_0002", "Read two, again", now)!!.version)
        // Saving unchanged text keeps the version.
        assertEquals(3, s.editText(Languages.SHONA, "names_0002", "Read two, again", now)!!.version)
    }

    @Test
    fun editWithUnknownIdOrBlankTextSavesNothing() {
        val s = store()
        assertNull(s.editText(Languages.SHONA, "missing", "x", now))
        assertNull(s.editText(Languages.SHONA, "names_0001", "   ", now))
        assertFalse(File(tmp.root, "sn/sn_read_v1.json").exists())
    }

    @Test
    fun withTextIsTheSingleVersionRule() {
        val p = Prompt("id", "c", "Old")
        assertEquals(Prompt("id", "c", "New", version = 2, editedAtUtc = now), p.withText("New", now))
        assertTrue(p.withText(" Old ", now) === p)
        // Stored text with outer spaces, saved back unchanged, keeps its version.
        val spaced = Prompt("id", "c", "Old \n")
        assertTrue(spaced.withText("Old", now) === spaced)
    }

    @Test
    fun eachLanguageEditsOnlyItsOwnActivePack() {
        val s = store()
        s.editText(Languages.NDEBELE, "nd_own_words_0001", "Her own words", now)
        assertEquals(2, s.load(Languages.NDEBELE).prompts.first { it.id == "nd_own_words_0001" }.version)
        assertFalse(s.load(Languages.SHONA).edited)
        assertNull(s.editText(Languages.SHONA, "nd_own_words_0001", "x", now))
    }

    @Test
    fun switchingPacksNeverLosesEdits() {
        val s = store()
        s.editText(Languages.SHONA, "names_0001", "Read one, fixed", now)

        assertTrue(s.setActivePack(Languages.SHONA, "sn_freeform_v1"))
        assertEquals("Cue one", s.load(Languages.SHONA).prompts.first().text)
        s.editText(Languages.SHONA, "own_words_0001", "Typed words", now)

        s.setActivePack(Languages.SHONA, "sn_read_v1")
        assertEquals("Read one, fixed", s.load(Languages.SHONA).prompts.first().text)

        s.setActivePack(Languages.SHONA, "sn_freeform_v1")
        assertEquals("Typed words", s.load(Languages.SHONA).prompts.first { it.id == "own_words_0001" }.text)

        // Another language's pack cannot be chosen.
        assertFalse(s.setActivePack(Languages.SHONA, "nd_freeform_v1"))
    }

    @Test
    fun importNamespacesIdsSoTheyStayUniqueAcrossLanguages() {
        val s = store()
        val nd = s.importScript(Languages.NDEBELE, script("mine", "sn", "cue_0001" to "One", "nd_cue_0002" to "Two"), "mine.json", 1L)!!
        assertEquals(nd.id, s.activePack(Languages.NDEBELE).id)
        assertEquals("Imported: mine.json", nd.label)
        assertEquals(listOf("nd_cue_0001", "nd_cue_0002"), s.load(Languages.NDEBELE).prompts.map { it.id })
        assertEquals("mine", s.load(Languages.NDEBELE).scriptVersion)

        // Plain-text lines are numbered under their pack's id, so a second
        // text import never shares an id with the first.
        s.importScript(Languages.NDEBELE, "First line\n\nSecond line\n", "lines.txt", 2L)
        assertEquals(listOf("nd_import_2_1", "nd_import_2_2"), s.load(Languages.NDEBELE).prompts.map { it.id })
        s.importScript(Languages.NDEBELE, "Other line\n", "more.txt", 5L)
        assertEquals(listOf("nd_import_5_1"), s.load(Languages.NDEBELE).prompts.map { it.id })
        s.setActivePack(Languages.NDEBELE, "nd_import_2")

        // Shona keeps bare ids, as v0.6.0 did, and never takes another language's prefix.
        s.importScript(Languages.SHONA, script("x", "sn", "cue_0001" to "One", "nd_cue_0003" to "Two"), null, 3L)
        assertEquals(listOf("cue_0001", "sn_nd_cue_0003"), s.load(Languages.SHONA).prompts.map { it.id })

        assertNull(s.importScript(Languages.SHONA, "\n \n", "empty.txt", 4L))
        // Imports stay in the chooser after switching away.
        assertEquals(listOf("nd_freeform_v1", nd.id, "nd_import_2", "nd_import_5"), s.packs(Languages.NDEBELE).map { it.id })
        assertEquals("nd_import_2", s.activePack(Languages.NDEBELE).id)
    }

    @Test
    fun addedPromptsAreNamespacedAndDeletesPersist() {
        val s = store()
        val added = s.addPrompt(Languages.NDEBELE, "A new line")!!
        assertTrue(added.id.startsWith("nd_custom_"))
        s.deletePrompt(Languages.NDEBELE, "nd_own_words_0001")
        assertEquals(listOf("nd_free_form_cue_0001", added.id), store().load(Languages.NDEBELE).prompts.map { it.id })
    }

    @Test
    fun resetRestoresShippedTextWithoutReusingVersions() {
        val s = store()
        s.editText(Languages.SHONA, "names_0001", "Changed", now)
        s.addPrompt(Languages.SHONA, "Extra")
        assertTrue(s.resetActivePack(Languages.SHONA, "2026-10-27T11:00:00Z"))
        val restored = s.load(Languages.SHONA).prompts
        assertEquals(listOf("names_0001", "names_0002", "stock_0001"), restored.map { it.id })
        // Version 2 named "Changed"; the shipped text comes back as version 3.
        assertEquals("Read one", restored[0].text)
        assertEquals(3, restored[0].version)

        // A pack whose edits only ever added prompts goes back to no edited copy.
        s.setActivePack(Languages.SHONA, "sn_freeform_v1")
        s.addPrompt(Languages.SHONA, "Extra")
        assertTrue(s.resetActivePack(Languages.SHONA, now))
        assertFalse(s.load(Languages.SHONA).edited)

        s.importScript(Languages.SHONA, "Line", "lines.txt", 9L)
        assertFalse(s.resetActivePack(Languages.SHONA, now))
    }

    private fun writeLegacy(json: String): ByteArray {
        val legacy = File(tmp.root, "active_script.json")
        tmp.root.mkdirs()
        legacy.writeText(json)
        return legacy.readBytes()
    }

    @Test
    fun v060EditedReadScriptBecomesTheShonaSlotUnchanged() {
        // What v0.6.0's saveActive wrote after edits in its script editor:
        // script_version "custom", an edited text and an added prompt.
        val legacy = """{
  "script_version": "custom",
  "language": "sn",
  "prompts": [
    {"id": "names_0001", "category": "names", "text": "Read one, fixed by her"},
    {"id": "stock_0001", "category": "stock", "text": "Read three"},
    {"id": "custom_ab12cd34", "category": "custom", "text": "Her own prompt"}
  ]
}"""
        val bytes = writeLegacy(legacy)

        val s = store()
        val active = s.load(Languages.SHONA)
        assertEquals("sn_read_v1", active.pack.id)
        assertEquals("custom", active.scriptVersion)
        assertEquals(
            listOf(
                Prompt("names_0001", "names", "Read one, fixed by her"),
                Prompt("stock_0001", "stock", "Read three"),
                Prompt("custom_ab12cd34", "custom", "Her own prompt"),
            ),
            active.prompts,
        )
        assertArrayEquals(bytes, File(tmp.root, "sn/sn_read_v1.json").readBytes())
        assertFalse(File(tmp.root, "active_script.json").exists())
        assertArrayEquals(bytes, File(tmp.root, "active_script.v060.json").readBytes())
        assertEquals(listOf("sn_read_v1"), adopted)

        // Runs once.
        store().load(Languages.SHONA)
        assertEquals(listOf("sn_read_v1"), adopted)
        assertEquals("nd_freeform_v1", s.load(Languages.NDEBELE).pack.id)
    }

    @Test
    fun v060ImportedFreeFormMovesIntoTheFreeFormSlot() {
        writeLegacy(script("custom", "sn", "free_form_cue_0001" to "Cue one", "own_words_0001" to "Typed in v0.6.0"))
        val active = store().load(Languages.SHONA)
        assertEquals("sn_freeform_v1", active.pack.id)
        assertEquals("Typed in v0.6.0", active.prompts[1].text)
        assertEquals(listOf("sn_freeform_v1"), adopted)
    }

    @Test
    fun v060ScriptMatchingNoBundledPackKeepsItsOwnSlot() {
        writeLegacy(script("custom", "sn", "custom_1" to "Line one", "custom_2" to "Line two"))
        val s = store()
        val active = s.load(Languages.SHONA)
        assertEquals(ScriptStore.LEGACY_PACK_ID, active.pack.id)
        assertEquals("Script from v0.6.0", active.pack.label)
        assertEquals(listOf("custom_1", "custom_2"), active.prompts.map { it.id })
        // The bundled packs stay available beside it.
        assertEquals(listOf("sn_read_v1", "sn_freeform_v1", ScriptStore.LEGACY_PACK_ID), s.packs(Languages.SHONA).map { it.id })
    }

    @Test
    fun positionsResolveByPromptIdThenByIndex() {
        val prompts = listOf(Prompt("a", "c", "1"), Prompt("b", "c", "2"), Prompt("c", "c", "3"))
        assertEquals(2, Position(0, "c").resolveIn(prompts))
        assertEquals(1, Position(1, "deleted").resolveIn(prompts))
        assertEquals(3, Position(3, null).resolveIn(prompts))
        assertEquals(3, Position(99, null).resolveIn(prompts))
    }

    @Test
    fun namespacedIdRules() {
        assertEquals("names_0001", Languages.namespacedId("names_0001", Languages.SHONA))
        assertEquals("sn_nd_x", Languages.namespacedId("nd_x", Languages.SHONA))
        assertEquals("nd_x", Languages.namespacedId("x", Languages.NDEBELE))
        assertEquals("nd_x", Languages.namespacedId("nd_x", Languages.NDEBELE))
    }
}
