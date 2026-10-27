package com.quantilytix.izwi.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A script the app ships ([assetName] set) or one the speaker imported. */
data class ScriptPack(
    val id: String,
    val language: String,
    val label: String,
    val assetName: String? = null,
) {
    val isBundled get() = assetName != null
}

/** A language's active pack and its prompts. [scriptVersion] is what each
 * clip records; [edited] is true once a bundled pack has an edited copy. */
data class ActiveScript(
    val pack: ScriptPack,
    val scriptVersion: String,
    val prompts: List<Prompt>,
    val edited: Boolean,
)

/** A script file read into memory. [headerJson] holds every top-level
 * field except the prompts (note, language, ...), carried through on save. */
internal data class ParsedScript(
    val scriptVersion: String,
    val headerJson: String,
    val prompts: List<Prompt>,
)

/** The script JSON schema: script_version, language, note and prompts, each
 * prompt with id, category, text, and since v0.7.0 version and edited_at. */
internal object ScriptJson {

    fun parse(json: String): ParsedScript {
        val root = JSONObject(json)
        val array = root.getJSONArray("prompts")
        val prompts = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Prompt(
                id = obj.optString("id", "").ifBlank { "custom_${UUID.randomUUID().toString().take(8)}" },
                category = obj.optString("category", "custom"),
                text = obj.getString("text"),
                version = obj.optInt("version", 1).coerceAtLeast(1),
                editedAtUtc = obj.optString("edited_at", "").ifBlank { null },
            )
        }
        root.remove("prompts")
        return ParsedScript(root.optString("script_version", "custom"), root.toString(), prompts)
    }

    /** Plain text, one prompt per line, as v0.6.0 accepted on import. */
    fun parseLines(text: String): ParsedScript {
        val prompts = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, line -> Prompt(id = "custom_${i + 1}", category = "custom", text = line) }
        return ParsedScript("custom", "{}", prompts)
    }

    fun write(script: ParsedScript, language: String): String {
        val root = JSONObject(script.headerJson)
        root.put("script_version", script.scriptVersion)
        root.put("language", language)
        val array = JSONArray()
        for (p in script.prompts) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("category", p.category)
            obj.put("text", p.text)
            obj.put("version", p.version)
            if (p.editedAtUtc != null) obj.put("edited_at", p.editedAtUtc)
            array.put(obj)
        }
        root.put("prompts", array)
        return root.toString(2)
    }
}

/**
 * Script packs on disk, per language. Plain JVM code, so the unit tests can
 * run it against a temporary folder; ScriptRepository wires it to the app's
 * files dir and assets.
 *
 * Layout under [root]:
 *  - state.json: the active pack per language and the imported packs.
 *  - <language>/<packId>.json: a pack's edited copy. A bundled pack gets
 *    one on its first edit and keeps it when another pack is chosen, so
 *    switching packs never loses edits. Imported packs live only here.
 *  - active_script.json: v0.6.0's single script. The first run copies it
 *    byte for byte into the Shona slot and keeps it as active_script.v060.json.
 *
 * [onLegacyAdopted] runs once, on that first run, with the Shona pack id the
 * v0.6.0 script and saved positions now belong to.
 */
class ScriptStore(
    private val root: File,
    private val readAsset: (String) -> String,
    private val onLegacyAdopted: (String) -> Unit = {},
) {

    companion object {
        val BUNDLED_PACKS = listOf(
            ScriptPack("sn_read_v1", Languages.SHONA, "Shona read script", "script_v1.json"),
            ScriptPack("sn_freeform_v1", Languages.SHONA, "Shona free-form", "script_freeform_v1.json"),
            ScriptPack("nd_freeform_v1", Languages.NDEBELE, "isiNdebele free-form", "script_nd_freeform_v1.json"),
        )

        /** The Shona slot for a v0.6.0 script that matches no bundled pack,
         * such as an imported plain-text file. */
        const val LEGACY_PACK_ID = "sn_v060"
        private const val LEGACY_FILE = "active_script.json"
        private const val LEGACY_BACKUP = "active_script.v060.json"

        // One lock and one parsed-script cache per process. The recorder and
        // the Prompts screen each hold their own store, and an edit from one
        // must never interleave with a write from the other.
        private val LOCK = Any()
        private val cache = HashMap<String, CachedScript>()
    }

    private class CachedScript(val stamp: Long, val size: Long, val script: ParsedScript)

    private class State(val active: MutableMap<String, String>, val imports: MutableList<ScriptPack>)

    private val stateFile get() = File(root, "state.json")

    fun packs(language: String): List<ScriptPack> = synchronized(LOCK) {
        ensureMigrated()
        packsLocked(language, readState())
    }

    fun activePack(language: String): ScriptPack = synchronized(LOCK) {
        ensureMigrated()
        activePackLocked(language, readState())
    }

    fun setActivePack(language: String, packId: String): Boolean {
        synchronized(LOCK) {
            ensureMigrated()
            val state = readState()
            if (packsLocked(language, state).none { it.id == packId }) return false
            state.active[language] = packId
            writeState(state)
            return true
        }
    }

    fun load(language: String): ActiveScript = synchronized(LOCK) {
        ensureMigrated()
        val pack = activePackLocked(language, readState())
        val parsed = parsePack(pack) ?: ParsedScript("custom", "{}", emptyList())
        ActiveScript(pack, parsed.scriptVersion, parsed.prompts, pack.isBundled && editedFile(pack).exists())
    }

    /** True when [pack] has an edited copy on disk. */
    fun hasEdits(pack: ScriptPack): Boolean = synchronized(LOCK) { pack.isBundled && editedFile(pack).exists() }

    /**
     * The one save path for prompt text, used by the Prompts screen and the
     * recorder alike. Saves at once into the language's active pack, keeps
     * the id, bumps the version and stamps [nowUtc]. Takes are untouched:
     * each keeps the transcript and version it was recorded with. Returns
     * the saved prompt, or null when the active pack has no such prompt.
     */
    fun editText(language: String, promptId: String, newText: String, nowUtc: String): Prompt? {
        synchronized(LOCK) {
            if (newText.isBlank()) return null
            ensureMigrated()
            val pack = activePackLocked(language, readState())
            val parsed = parsePack(pack) ?: return null
            val i = parsed.prompts.indexOfFirst { it.id == promptId }
            if (i < 0) return null
            val old = parsed.prompts[i]
            val updated = old.withText(newText, nowUtc)
            if (updated === old) return old
            val prompts = parsed.prompts.toMutableList().also { it[i] = updated }
            saveLocked(pack, parsed.copy(prompts = prompts))
            return updated
        }
    }

    fun addPrompt(language: String, text: String): Prompt? {
        synchronized(LOCK) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            ensureMigrated()
            val pack = activePackLocked(language, readState())
            val parsed = parsePack(pack) ?: ParsedScript("custom", "{}", emptyList())
            val prompt = Prompt(
                id = Languages.namespacedId("custom_${UUID.randomUUID().toString().take(8)}", language),
                category = "custom",
                text = trimmed,
            )
            saveLocked(pack, parsed.copy(prompts = parsed.prompts + prompt))
            return prompt
        }
    }

    fun deletePrompt(language: String, promptId: String) {
        synchronized(LOCK) {
            ensureMigrated()
            val pack = activePackLocked(language, readState())
            val parsed = parsePack(pack) ?: return
            if (parsed.prompts.none { it.id == promptId }) return
            saveLocked(pack, parsed.copy(prompts = parsed.prompts.filter { it.id != promptId }))
        }
    }

    /**
     * Adds a pack from a file the speaker picked: the app's JSON schema, or
     * plain text with one prompt per line. Ids are namespaced for [language]
     * so they never collide with another language's. The new pack becomes
     * the language's active pack. Returns null when the file holds no prompts.
     */
    fun importScript(language: String, content: String, sourceName: String?, nowMillis: Long): ScriptPack? {
        synchronized(LOCK) {
            ensureMigrated()
            var fromLines = false
            val parsed = try {
                ScriptJson.parse(content)
            } catch (e: Exception) {
                fromLines = true
                ScriptJson.parseLines(content)
            }
            if (parsed.prompts.isEmpty()) return null
            val state = readState()
            var id = "${language}_import_$nowMillis"
            while (packsLocked(language, state).any { it.id == id }) id += "_1"
            val label = "Imported: " + (sourceName?.takeIf { it.isNotBlank() } ?: parsed.scriptVersion)
            val pack = ScriptPack(id, language, label)
            // Plain-text lines carry no ids. Numbering them under the pack id
            // keeps two text imports from both holding custom_1, which would
            // give one file's prompts the other file's takes and status.
            val prompts = parsed.prompts.mapIndexed { i, p ->
                p.copy(id = if (fromLines) "${pack.id}_${i + 1}" else Languages.namespacedId(p.id, language))
            }
            saveLocked(pack, parsed.copy(prompts = prompts))
            state.imports.add(pack)
            state.active[language] = pack.id
            writeState(state)
            return pack
        }
    }

    /**
     * Puts a bundled pack's shipped text back. A prompt whose text changes
     * gets a new version rather than version 1 again, so an id and version
     * always name one text and older takes still read as recorded against
     * other text. Added prompts go; deleted ones return. Imported packs have
     * no shipped original: returns false and changes nothing.
     */
    fun resetActivePack(language: String, nowUtc: String): Boolean {
        synchronized(LOCK) {
            ensureMigrated()
            val pack = activePackLocked(language, readState())
            val asset = pack.assetName ?: return false
            val file = editedFile(pack)
            if (!file.exists()) return true
            val current = (parsePack(pack)?.prompts ?: emptyList()).associateBy { it.id }
            val original = parseAsset(asset)
            val restored = original.prompts.map { shipped ->
                val now = current[shipped.id]
                when {
                    now == null -> shipped
                    now.text == shipped.text -> now
                    else -> shipped.copy(version = now.version + 1, editedAtUtc = nowUtc)
                }
            }
            if (restored.all { it.version == 1 }) {
                file.delete()
                cache.remove(file.absolutePath)
            } else {
                saveLocked(pack, original.copy(prompts = restored))
            }
            return true
        }
    }

    /**
     * First run on this install. A v0.6.0 script moves into the Shona slot:
     * the bundled Shona pack most of its ids come from (her edited read
     * script, or the free-form pack she imported), else [LEGACY_PACK_ID].
     * The file is copied byte for byte, so every edit stays exactly as she
     * left it, and its prompts read as version 1.
     */
    private fun ensureMigrated() {
        if (stateFile.exists()) return
        val state = State(mutableMapOf(), mutableListOf())
        var shonaPack = BUNDLED_PACKS.first { it.language == Languages.SHONA }.id
        val legacy = File(root, LEGACY_FILE)
        val legacyIds = if (legacy.exists()) {
            try {
                ScriptJson.parse(legacy.readText()).prompts.map { it.id }
            } catch (e: Exception) {
                null // unreadable: v0.6.0 could not load it either, so leave it where it is
            }
        } else null
        if (legacyIds != null) {
            shonaPack = matchBundledPack(Languages.SHONA, legacyIds) ?: LEGACY_PACK_ID.also {
                state.imports.add(ScriptPack(it, Languages.SHONA, "Script from v0.6.0"))
            }
            val target = File(root, "${Languages.SHONA}/$shonaPack.json")
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = true)
            legacy.renameTo(File(root, LEGACY_BACKUP))
        }
        state.active[Languages.SHONA] = shonaPack
        writeState(state)
        onLegacyAdopted(shonaPack)
    }

    private fun matchBundledPack(language: String, ids: List<String>): String? {
        val idSet = ids.toSet()
        if (idSet.isEmpty()) return null
        return BUNDLED_PACKS.filter { it.language == language }
            .map { pack -> pack to parseAsset(pack.assetName!!).prompts.count { it.id in idSet } }
            .filter { (_, hits) -> hits * 2 >= idSet.size }
            .maxByOrNull { (_, hits) -> hits }
            ?.first?.id
    }

    private fun packsLocked(language: String, state: State): List<ScriptPack> =
        BUNDLED_PACKS.filter { it.language == language } + state.imports.filter { it.language == language }

    private fun activePackLocked(language: String, state: State): ScriptPack {
        val packs = packsLocked(language, state)
        val chosen = state.active[language]
        return packs.firstOrNull { it.id == chosen && (it.isBundled || editedFile(it).exists()) }
            ?: packs.firstOrNull { it.isBundled }
            ?: ScriptPack("${language}_custom", language, "Custom script")
    }

    private fun editedFile(pack: ScriptPack) = File(root, "${pack.language}/${pack.id}.json")

    private fun parsePack(pack: ScriptPack): ParsedScript? {
        val file = editedFile(pack)
        if (file.exists()) {
            val key = file.absolutePath
            cache[key]?.let { if (it.stamp == file.lastModified() && it.size == file.length()) return it.script }
            val parsed = ScriptJson.parse(file.readText())
            cache[key] = CachedScript(file.lastModified(), file.length(), parsed)
            return parsed
        }
        return pack.assetName?.let { parseAsset(it) }
    }

    private fun parseAsset(assetName: String): ParsedScript {
        // Keyed by root too, so stores over different folders (the unit
        // tests) never share an asset entry.
        val key = "asset:${root.absolutePath}:$assetName"
        cache[key]?.let { return it.script }
        val parsed = ScriptJson.parse(readAsset(assetName))
        cache[key] = CachedScript(0, 0, parsed)
        return parsed
    }

    private fun saveLocked(pack: ScriptPack, script: ParsedScript) {
        val file = editedFile(pack)
        writeAtomically(file, ScriptJson.write(script, pack.language))
        cache[file.absolutePath] = CachedScript(file.lastModified(), file.length(), script)
    }

    private fun readState(): State {
        val state = State(mutableMapOf(), mutableListOf())
        if (!stateFile.exists()) return state
        val obj = JSONObject(stateFile.readText())
        obj.optJSONObject("active")?.let { active ->
            for (key in active.keys()) state.active[key] = active.getString(key)
        }
        obj.optJSONArray("imports")?.let { imports ->
            for (i in 0 until imports.length()) {
                val p = imports.getJSONObject(i)
                state.imports.add(ScriptPack(p.getString("id"), p.getString("language"), p.getString("label")))
            }
        }
        return state
    }

    private fun writeState(state: State) {
        val obj = JSONObject()
        obj.put("active", JSONObject(state.active as Map<*, *>))
        val imports = JSONArray()
        for (p in state.imports) {
            imports.put(JSONObject().put("id", p.id).put("language", p.language).put("label", p.label))
        }
        obj.put("imports", imports)
        writeAtomically(stateFile, obj.toString(2))
    }

    /** Write then rename, so a crash mid-save leaves the old file whole
     * instead of a truncated script holding her edits. */
    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
