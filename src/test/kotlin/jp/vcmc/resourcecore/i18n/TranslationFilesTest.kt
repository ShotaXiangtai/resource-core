package jp.vcmc.resourcecore.i18n

import jp.vcmc.resourcecore.logic.formatDuration
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationFilesTest {
    private val japanese = TranslationFilesCheck.resource("messages.yml")

    @Test
    fun `どの言語にも日本語と同じ文面がある`() {
        TranslationFilesCheck.check(TranslationFilesCheck.texts(japanese))
    }

    @Test
    fun `時間の表記は言語ごとの単位と区切りを使う`() {
        fun format(language: Language): String {
            val file = TranslationFilesCheck.translated.getValue(language)
            return formatDuration(
                3930,
                file.getString("time.hour").orEmpty(),
                file.getString("time.minute").orEmpty(),
                file.getString("time.second").orEmpty(),
                file.getString("time.separator").orEmpty(),
            )
        }
        assertEquals("1h 5m 30s", format(Language.ENGLISH))
        assertEquals("1小时5分钟30秒", format(Language.SIMPLIFIED_CHINESE))
        assertEquals("1小時5分鐘30秒", format(Language.TRADITIONAL_CHINESE_TAIWAN))
        assertEquals("1小時5分鐘30秒", format(Language.TRADITIONAL_CHINESE_HONG_KONG))
    }
}
