package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.logic.CountdownStep
import jp.vcmc.resourcecore.logic.RelocateTarget
import jp.vcmc.resourcecore.logic.ResourceType
import net.kyori.adventure.key.InvalidKeyException
import net.kyori.adventure.key.Key
import org.bukkit.configuration.ConfigurationSection
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class WorldSettings(val type: ResourceType, val key: Key, val seed: Long?, val changeSeed: Boolean)

data class ResetSettings(val countdown: List<CountdownStep>, val backup: Boolean, val moveTimeoutSeconds: Int)

data class Settings(
    val worlds: Map<ResourceType, WorldSettings>,
    val reset: ResetSettings,
    val borderRadius: Int,
    val pregenEnabled: Boolean,
    val relocateTarget: RelocateTarget,
    val zone: ZoneId,
    val dateFormat: DateTimeFormatter,
) {
    fun world(type: ResourceType): WorldSettings = worlds.getValue(type)
    fun typeOf(key: Key): ResourceType? = worlds.values.firstOrNull { it.key == key }?.type
}

/** 設定の問題 1 件。ログにそのまま出す。 */
data class ConfigIssue(val path: String, val message: String)

data class ParsedSettings(val settings: Settings, val issues: List<ConfigIssue>)

object SettingsParser {
    private val DEFAULT_COUNTDOWN = listOf(CountdownStep(300), CountdownStep(60), CountdownStep(10))
    private const val DEFAULT_BORDER_RADIUS = 10_000
    // バニラのワールドボーダーの最大サイズ 59,999,968 の半分。
    private const val MAX_BORDER_RADIUS = 29_999_984
    private const val DEFAULT_ZONE = "Asia/Tokyo"
    private const val DEFAULT_DATE_FORMAT = "yyyy/MM/dd HH:mm"

    fun parse(config: ConfigurationSection): ParsedSettings {
        val issues = mutableListOf<ConfigIssue>()
        val settings = Settings(
            worlds = parseWorlds(config, issues),
            reset = parseReset(config, issues),
            borderRadius = parseBorderRadius(config, issues),
            pregenEnabled = config.getBoolean("pregen.enabled", true),
            relocateTarget = parseChoice(
                config, "login.relocate-target", RelocateTarget.MAIN_WORLD, issues,
                mapOf(
                    "main-world" to RelocateTarget.MAIN_WORLD,
                    "resource-spawn" to RelocateTarget.RESOURCE_SPAWN,
                    // 1.0.0 の設定ファイルをそのまま使えるよう残す。ハブは廃止したのでメインワールドへ送る。
                    "hub" to RelocateTarget.MAIN_WORLD,
                ),
            ),
            zone = parseZone(config, issues),
            dateFormat = parseDateFormat(config, issues),
        )
        return ParsedSettings(settings, issues)
    }

    /** 数値はそのまま、文字列はバニラと同じく hashCode を seed にする。空なら未指定（ランダム）。 */
    fun parseSeed(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.toLongOrNull() ?: text.hashCode().toLong()
    }

    private fun parseBorderRadius(config: ConfigurationSection, issues: MutableList<ConfigIssue>): Int {
        val radius = config.getInt("border.radius", DEFAULT_BORDER_RADIUS)
        if (radius in 1..MAX_BORDER_RADIUS) return radius
        issues += ConfigIssue("border.radius", "$radius は 1〜$MAX_BORDER_RADIUS にしてください。既定値 $DEFAULT_BORDER_RADIUS を使います")
        return DEFAULT_BORDER_RADIUS
    }

    private fun parseWorlds(config: ConfigurationSection, issues: MutableList<ConfigIssue>): Map<ResourceType, WorldSettings> {
        val parsed = ResourceType.entries.associateWith { type ->
            val base = "worlds.${type.id}"
            WorldSettings(
                type = type,
                key = parseWorldKey(config, "$base.key", type, issues),
                seed = parseSeed(config.getString("$base.seed")),
                changeSeed = config.getBoolean("$base.change-seed", true),
            )
        }.toMutableMap()

        // 重複したキーは同じワールドを二重に管理してしまうので、既定値へ戻す。
        val seen = mutableSetOf<Key>()
        for (type in ResourceType.entries) {
            val current = parsed.getValue(type)
            if (!seen.add(current.key)) {
                issues += ConfigIssue(
                    "worlds.${type.id}.key",
                    "'${current.key.asString()}' は他の資源ワールドと重複しています。既定値 ${type.defaultKey} を使います",
                )
                val fallback = Key.key(type.defaultKey)
                parsed[type] = current.copy(key = fallback)
                seen += fallback
            }
        }

        val namespaces = parsed.values.map { it.key.namespace() }.toSet()
        if (namespaces.size > 1) {
            issues += ConfigIssue(
                "worlds",
                "資源ワールドの namespace が揃っていません $namespaces。Worlds は同じ namespace のワールド同士しか連携しないため、ポータルは無効になります",
            )
        }
        return parsed
    }

    private fun parseWorldKey(
        config: ConfigurationSection,
        path: String,
        type: ResourceType,
        issues: MutableList<ConfigIssue>,
    ): Key {
        val fallback = Key.key(type.defaultKey)
        val raw = config.getString(path)?.trim()
        if (raw.isNullOrEmpty()) return fallback
        val key = try {
            Key.key(raw)
        } catch (_: InvalidKeyException) {
            issues += ConfigIssue(path, "'$raw' はワールドキーとして不正です。既定値 ${type.defaultKey} を使います")
            return fallback
        }
        if (key.namespace() == Key.MINECRAFT_NAMESPACE) {
            issues += ConfigIssue(path, "'$raw' は minecraft 名前空間のためサバイバル側と干渉します。既定値 ${type.defaultKey} を使います")
            return fallback
        }
        return key
    }

    private fun parseReset(config: ConfigurationSection, issues: MutableList<ConfigIssue>): ResetSettings {
        val countdown = if (config.isList("reset.countdown")) {
            parseCountdown(config.getMapList("reset.countdown"), issues)
        } else {
            if (config.contains("reset.countdown")) {
                issues += ConfigIssue("reset.countdown", "リスト形式で指定してください。既定値 (300, 60, 10 秒) を使います")
            }
            DEFAULT_COUNTDOWN
        }
        var timeout = config.getInt("reset.move-timeout-seconds", 30)
        if (timeout < 1) {
            issues += ConfigIssue("reset.move-timeout-seconds", "$timeout は 1 以上にしてください。既定値 30 を使います")
            timeout = 30
        }
        return ResetSettings(countdown, config.getBoolean("reset.backup", false), timeout)
    }

    private fun parseCountdown(entries: List<Map<*, *>>, issues: MutableList<ConfigIssue>): List<CountdownStep> {
        val steps = linkedMapOf<Int, CountdownStep>()
        entries.forEachIndexed { index, entry ->
            val path = "reset.countdown[$index]"
            val seconds = (entry["seconds"] as? Number)?.toInt()
            if (seconds == null || seconds <= 0) {
                issues += ConfigIssue(path, "seconds には 1 以上の数値を指定してください。この項目は無視します")
                return@forEachIndexed
            }
            if (seconds in steps) {
                issues += ConfigIssue(path, "$seconds 秒が重複しています。この項目は無視します")
                return@forEachIndexed
            }
            steps[seconds] = CountdownStep(seconds, (entry["message"] as? String)?.takeIf { it.isNotBlank() })
        }
        return steps.values.sortedByDescending { it.seconds }
    }

    private fun parseZone(config: ConfigurationSection, issues: MutableList<ConfigIssue>): ZoneId {
        val raw = config.getString("display.timezone") ?: DEFAULT_ZONE
        return try {
            ZoneId.of(raw)
        } catch (_: DateTimeException) {
            issues += ConfigIssue("display.timezone", "'$raw' は不明なタイムゾーンです。$DEFAULT_ZONE を使います")
            ZoneId.of(DEFAULT_ZONE)
        }
    }

    private fun parseDateFormat(config: ConfigurationSection, issues: MutableList<ConfigIssue>): DateTimeFormatter {
        val raw = config.getString("display.date-format") ?: DEFAULT_DATE_FORMAT
        return try {
            DateTimeFormatter.ofPattern(raw)
        } catch (_: IllegalArgumentException) {
            issues += ConfigIssue("display.date-format", "'$raw' は日時の書式として不正です。$DEFAULT_DATE_FORMAT を使います")
            DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT)
        }
    }

    private fun <T> parseChoice(
        config: ConfigurationSection,
        path: String,
        default: T,
        issues: MutableList<ConfigIssue>,
        values: Map<String, T>,
    ): T {
        val raw = config.getString(path) ?: return default
        return values[raw.trim().lowercase()] ?: run {
            issues += ConfigIssue(path, "'$raw' は不正です。${values.keys} のいずれかを指定してください。既定値を使います")
            default
        }
    }
}
