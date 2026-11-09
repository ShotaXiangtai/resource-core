package jp.vcmc.resourcecore.i18n

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** jar に入る文面ファイルの確認に使う。 */
object TranslationFilesCheck {
    fun resource(name: String): YamlConfiguration {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(name), name)
        return InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }

    val translated: Map<Language, YamlConfiguration> =
        Language.entries.filter { it != Language.JAPANESE }.associateWith { resource("lang/${it.code}.yml") }

    /** キーと文面。リストは改行でつなぐ。 */
    fun texts(section: ConfigurationSection): Map<String, String> =
        section.getKeys(true).filterNot(section::isConfigurationSection).associateWith { key ->
            if (section.isList(key)) section.getStringList(key).joinToString("\n") else section.getString(key).orEmpty()
        }

    /** MiniMessage の色や装飾のタグ。プレースホルダーではない。 */
    private val STYLE_TAGS = setOf(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
        "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
        "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
        "reset", "newline", "br",
    )

    private val MINI_TAG = Regex("""(?<!\\)</?([a-z0-9_-]+)>""")
    private val OTHER_PLACEHOLDER = Regex("""%[a-z_]+%|\{[a-z_]+}""")

    /** 文面の中のプレースホルダー。\< で書いたものは文字なので含めない。 */
    fun placeholders(text: String): Set<String> =
        MINI_TAG.findAll(text).map { it.groupValues[1] }.filterNot { it in STYLE_TAGS }.map { "<$it>" }.toSet() +
            OTHER_PLACEHOLDER.findAll(text).map { it.value }

    private val KANA = Regex("""[぀-ヺー-ヿ]""")
    private val CJK = Regex("""[぀-ヿ㐀-鿿]""")

    /** 広東語の口語にだけ使う字。繁體中文（香港）も書き言葉で書く。 */
    private val CANTONESE = Regex("[嘅咗喺唔冇嗰㗎搵攞睇佢哋撳閂揀晒噉啲嚟乜咁嘢諗畀]")

    /** 繁體中文（香港）で使わない語と、代わりに使う語。Minecraft 公式の繁體中文（香港）に合わせる。 */
    private val HONG_KONG_TERMS = mapOf(
        "指令" to "命令",
        "清單" to "列表",
        "資料夾" to "文件夾",
        "資料庫" to "數據庫",
        "主控台" to "控制台",
        "床" to "牀",
    )

    /**
     * @param japanese 日本語の文面。キーと文面の組。
     * @param ignore 比べない文面（コマンドの書式など）。
     */
    fun check(japanese: Map<String, String>, ignore: (String) -> Boolean = { false }) {
        assertTrue(japanese.isNotEmpty())
        translated.forEach { (language, file) ->
            val texts = texts(file)
            assertEquals(japanese.keys.sorted(), texts.keys.sorted(), "${language.code} のキーが日本語と違います")
            texts.forEach { (key, text) ->
                val where = "${language.code}: $key"
                val lines = text.split('\n').filterNot(ignore).joinToString("\n")
                val base = japanese.getValue(key).split('\n').filterNot(ignore).joinToString("\n")
                assertEquals(placeholders(base).sorted(), placeholders(lines).sorted(), "$where のプレースホルダーが日本語と違います")
                assertFalse(KANA.containsMatchIn(text), "$where に仮名が残っています: $text")
                if (language == Language.ENGLISH) assertFalse(CJK.containsMatchIn(text), "$where に漢字が残っています: $text")
                assertFalse(CANTONESE.containsMatchIn(text), "$where に広東語の口語があります: $text")
                if (language == Language.TRADITIONAL_CHINESE_HONG_KONG) {
                    HONG_KONG_TERMS.forEach { (avoid, use) -> assertFalse(avoid in text, "$where は「$avoid」ではなく「$use」を使います: $text") }
                }
                MiniMessage.miniMessage().deserialize(text)
                LegacyComponentSerializer.legacyAmpersand().deserialize(text)
            }
        }
    }
}
