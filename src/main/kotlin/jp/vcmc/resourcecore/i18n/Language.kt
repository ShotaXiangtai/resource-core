package jp.vcmc.resourcecore.i18n

import net.kyori.adventure.audience.Audience
import org.bukkit.entity.Player
import java.util.Locale

/** 対応している言語。code は lang フォルダのファイル名に使う。中国語はクライアントの言語コードと同じ。 */
enum class Language(val code: String) {
    JAPANESE("ja"),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh_cn"),
    TRADITIONAL_CHINESE_TAIWAN("zh_tw"),
    TRADITIONAL_CHINESE_HONG_KONG("zh_hk");

    companion object {
        /** コンソールなど、プレイヤー以外へ送るときの言語。 */
        val DEFAULT = JAPANESE

        /** Minecraft の言語設定から決める。対応していない言語のプレイヤーには English を使う。 */
        fun of(locale: Locale): Language = when (locale.language) {
            "ja" -> JAPANESE
            "zh" -> when (locale.country) {
                "TW" -> TRADITIONAL_CHINESE_TAIWAN
                "HK", "MO" -> TRADITIONAL_CHINESE_HONG_KONG
                else -> SIMPLIFIED_CHINESE
            }
            // 文言（lzh）は繁体字なので繁體中文（台灣）に寄せる。
            "lzh" -> TRADITIONAL_CHINESE_TAIWAN
            else -> ENGLISH
        }

        /** プレイヤーならその言語設定、それ以外なら既定の言語。言語設定は変更後すぐに反映される。どのスレッドから呼んでもよい。 */
        fun of(target: Audience): Language = if (target is Player) of(target.locale()) else DEFAULT
    }
}
