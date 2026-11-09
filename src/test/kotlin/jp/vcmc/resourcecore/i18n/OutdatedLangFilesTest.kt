package jp.vcmc.resourcecore.i18n

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutdatedLangFilesTest {
    private val old = "# 以前の文面\nkey: \"old\"\n"
    private val oldHashes = setOf(OutdatedLangFiles.hash(old.toByteArray()))

    private fun withDir(block: (File) -> Unit) {
        val dir = createTempDirectory("lang").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `改行コードが違っても同じハッシュになる`() {
        assertEquals(OutdatedLangFiles.hash(old.toByteArray()), OutdatedLangFiles.hash(old.replace("\n", "\r\n").toByteArray()))
        assertNotEquals(OutdatedLangFiles.hash(old.toByteArray()), OutdatedLangFiles.hash("key: \"new\"\n".toByteArray()))
    }

    @Test
    fun `以前の既定の文面のままなら移す`() = withDir { dir ->
        val windows = old.replace("\n", "\r\n")
        val file = File(dir, "zh_hk.yml").apply { writeText(windows) }
        val backup = assertNotNull(OutdatedLangFiles.moveAside(file, oldHashes))
        assertFalse(file.exists())
        assertEquals("zh_hk.yml.old", backup.name)
        assertEquals(windows, backup.readText())
    }

    @Test
    fun `編集されたファイルは移さない`() = withDir { dir ->
        val file = File(dir, "zh_hk.yml").apply { writeText(old + "edited: true\n") }
        assertNull(OutdatedLangFiles.moveAside(file, oldHashes))
        assertTrue(file.exists())
    }

    @Test
    fun `一度移したあとは以前の文面に戻されても移さない`() = withDir { dir ->
        val file = File(dir, "zh_hk.yml").apply { writeText(old) }
        File(dir, "zh_hk.yml.old").writeText(old)
        assertNull(OutdatedLangFiles.moveAside(file, oldHashes))
        assertTrue(file.exists())
    }

    @Test
    fun `ファイルが無ければ何もしない`() = withDir { dir ->
        assertNull(OutdatedLangFiles.moveAside(File(dir, "zh_hk.yml"), oldHashes))
    }

    @Test
    fun `一覧のコメントと空行は無視する`() {
        val parsed = OutdatedLangFiles.parse("# コメント\n\nzh_hk.yml ABC # 1.2.0\nzh_hk.yml def\n")
        assertEquals(mapOf("zh_hk.yml" to setOf("abc", "def")), parsed)
    }

    @Test
    fun `同梱の一覧は正しい形で今の文面を含まない`() {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("lang/outdated.txt"))
        val outdated = OutdatedLangFiles.parse(stream.use { String(it.readBytes(), Charsets.UTF_8) })
        assertTrue(outdated.isNotEmpty())
        outdated.forEach { (name, hashes) ->
            hashes.forEach { assertTrue(it.matches(Regex("[0-9a-f]{64}")), "$name: $it") }
            val current = assertNotNull(javaClass.classLoader.getResourceAsStream("lang/$name"), name).use { it.readBytes() }
            assertFalse(OutdatedLangFiles.hash(current) in hashes, "$name の今の文面が一覧にあります")
        }
    }
}
