package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoicePackCatalogTest {
    /** 单元测试的工作目录是 app/。 */
    private val bundled = File("src/main/assets/voice-models/catalog.json").readText()

    private fun minimal(extraRoot: String = "", extraPack: String = "", extraFile: String = "") = """
        {
          "schema": 1, $extraRoot
          "release": "https://example.com/r/",
          "packs": [
            ${pack("a", "sherpa-onnx", "whisper", extraPack, extraFile)},
            ${pack("b", "sherpa-onnx", "future", "", "")},
            ${pack("c", "other-backend", "whisper", "", "")}
          ]
        }
    """.trimIndent()

    private fun pack(id: String, backend: String, family: String, extraPack: String, extraFile: String) = """
        {
          "id": "$id", "version": 1, "backend": "$backend", "family": "$family", "displayName": "$id",
          "languages": ["en"], "recommendedFor": ["en"], $extraPack
          "files": [
            { "name": "m.onnx", "role": "model", "size": 10, "sha256": "${"0".repeat(64)}" $extraFile },
            { "name": "LICENSE", "role": "license", "size": 5, "sha256": "${"1".repeat(64)}", "url": "https://example.com/LICENSE" }
          ],
          "license": { "name": "MIT", "file": "LICENSE", "attribution": "x" },
          "upstream": "https://up.example.com/$id/"
        }
    """.trimIndent()

    @Test
    fun bundledCatalogIsConsistent() {
        val c = VoicePackCatalog.parse(bundled) { _, _ -> true }
        assertEquals(4, c.packs.size)
        assertEquals(c.packs.size, c.packs.map { it.id }.toSet().size)
        val sha = Regex("^[0-9a-f]{64}$")
        c.packs.forEach { p ->
            val lic = p.files.filter { it.role == "license" }
            assertEquals(p.id, 1, lic.size)
            assertEquals(p.id, p.license.file, lic.single().name)
            p.files.forEach { f ->
                assertTrue("${p.id}/${f.name} sha256", sha.matches(f.sha256))
                assertTrue("${p.id}/${f.name} size", f.size > 0)
            }
            assertEquals(p.files.sumOf { it.size }, p.totalBytes)
        }
        val tags = AppLanguage.entries.mapNotNull { it.tag }.toSet()
        assertEquals(13, tags.size)
        assertEquals(tags, c.packs.flatMap { it.recommendedFor }.toSet())
    }

    @Test
    fun unknownBackendOrFamilyIsHidden() {
        val c = VoicePackCatalog.parse(minimal()) { b, f -> b == "sherpa-onnx" && f != "future" }
        assertEquals(listOf("a"), c.packs.map { it.id })
        assertNotNull(c.entry("a"))
        assertNull(c.entry("b"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val c = VoicePackCatalog.parse(
            minimal(extraRoot = "\"generatedAt\": \"2026\",", extraPack = "\"minApp\": 3,", extraFile = ", \"mirror\": [\"x\"]"),
        ) { _, _ -> true }
        assertEquals(3, c.packs.size)
    }

    @Test
    fun packWithUnsafeIdOrFileNameIsHidden() {
        val bad = listOf("", ".", "..", "../x", "a/b", "a\\\\b")
        bad.forEach { name ->
            // 只改 a 的文件名(LICENSE 三个包都有,改 a 那条带的 url 来定位)
            val json = minimal(extraFile = ", \"url\": \"https://example.com/a-model\"")
                .replace("\"name\": \"m.onnx\", \"role\": \"model\", \"size\": 10, \"sha256\": \"${"0".repeat(64)}\" , \"url\": \"https://example.com/a-model\"",
                    "\"name\": \"$name\", \"role\": \"model\", \"size\": 10, \"sha256\": \"${"0".repeat(64)}\"")
            val byFile = VoicePackCatalog.parse(json) { _, _ -> true }
            assertEquals("file name '$name'", listOf("b", "c"), byFile.packs.map { it.id })
            val byId = VoicePackCatalog.parse(minimal().replace("\"id\": \"a\"", "\"id\": \"$name\"")) { _, _ -> true }
            assertEquals("id '$name'", listOf("b", "c"), byId.packs.map { it.id })
        }
        assertTrue(VoicePackCatalog.isSafeName("model.int8.onnx"))
        assertFalse(VoicePackCatalog.isSafeName(".."))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownSchemaThrows() {
        VoicePackCatalog.parse(minimal().replace("\"schema\": 1", "\"schema\": 2")) { _, _ -> true }
    }

    @Test
    fun releaseUrlUsesPackPrefix() {
        val c = VoicePackCatalog.parse(bundled) { _, _ -> true }
        val e = c.entry("sensevoice-small-int8")!!
        val model = e.files.first { it.name == "model.int8.onnx" }
        assertEquals(c.release + "sensevoice-small-int8--model.int8.onnx", c.releaseUrl(e, model))
    }

    @Test
    fun upstreamUrlPrefersFileUrl() {
        val c = VoicePackCatalog.parse(bundled) { _, _ -> true }
        val e = c.entry("whisper-base-int8")!!
        val enc = e.files.first { it.role == "encoder" }
        val lic = e.files.first { it.role == "license" }
        assertEquals(e.upstream + "base-encoder.int8.onnx", c.upstreamUrl(e, enc))
        assertEquals(lic.url, c.upstreamUrl(e, lic))
        assertTrue(lic.url!!.startsWith("https://raw.githubusercontent.com/openai/whisper/"))
        assertEquals(c.release + "whisper-base-int8--LICENSE", c.releaseUrl(e, lic))
    }
}
