package jp.vcmc.resourcecore.i18n

import net.kyori.adventure.translation.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LanguageTest {
    /** サーバーと同じ方法で、クライアントの言語コードを Locale にする。 */
    private fun languageOf(code: String) = Language.of(assertNotNull(Translator.parseLocale(code), code))

    @Test
    fun `日本語`() {
        assertEquals(Language.JAPANESE, languageOf("ja_jp"))
    }

    @Test
    fun `英語の各地域`() {
        listOf("en_us", "en_gb", "en_au", "en_ca", "en_nz", "en_ud").forEach {
            assertEquals(Language.ENGLISH, languageOf(it), it)
        }
    }

    @Test
    fun `中国語は地域で分ける`() {
        assertEquals(Language.SIMPLIFIED_CHINESE, languageOf("zh_cn"))
        assertEquals(Language.TRADITIONAL_CHINESE_TAIWAN, languageOf("zh_tw"))
        assertEquals(Language.TRADITIONAL_CHINESE_HONG_KONG, languageOf("zh_hk"))
    }

    @Test
    fun `中国語のファイル名はクライアントの言語コードと同じ`() {
        listOf("zh_cn", "zh_tw", "zh_hk").forEach { assertEquals(it, languageOf(it).code) }
    }

    @Test
    fun `文言は繁體中文（台灣）にする`() {
        assertEquals(Language.TRADITIONAL_CHINESE_TAIWAN, languageOf("lzh"))
    }

    @Test
    fun `対応していない言語は英語にする`() {
        listOf("ko_kr", "fr_fr", "de_de", "es_es", "ru_ru", "enws").forEach {
            assertEquals(Language.ENGLISH, languageOf(it), it)
        }
    }
}
