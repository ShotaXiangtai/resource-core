package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.logic.CountdownStep
import jp.vcmc.resourcecore.logic.HubJoinMode
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

data class HubProtection(
    val blockBreak: Boolean,
    val blockPlace: Boolean,
    val pvp: Boolean,
    val hunger: Boolean,
    val fallDamage: Boolean,
    val mobSpawn: Boolean,
)

data class HubSettings(
    val key: Key?,
    val joinMode: HubJoinMode,
    val teleportDelaySeconds: Int,
    val cancelOnMove: Boolean,
    val protection: HubProtection,
)

data class Settings(
    val worlds: Map<ResourceType, WorldSettings>,
    val reset: ResetSettings,
    val hub: HubSettings,
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
    /** サバイバル用のデフォルトワールド。資源ワールドにもハブにも使わせない。 */
    val DEFAULT_WORLD_KEYS: Set<Key> = setOf(Key.key("overworld"), Key.key("the_nether"), Key.key("the_end"))

    private val DEFAULT_COUNTDOWN = listOf(CountdownStep(300), CountdownStep(60), CountdownStep(10))
    private const val DEFAULT_ZONE = "Asia/Tokyo"
    private const val DEFAULT_DATE_FORMAT = "yyyy/MM/dd HH:mm"

    enum class HubKeyProblem { DEFAULT_WORLD, RESOURCE_WORLD }

    fun parse(config: ConfigurationSection): ParsedSettings {
        val issues = mutableListOf<ConfigIssue>()
        val worlds = parseWorlds(config, issues)
        val settings = Settings(
            worlds = worlds,
            reset = parseReset(config, issues),
            hub = parseHub(config, worlds, issues),
            relocateTarget = parseChoice(
                config, "login.relocate-target", RelocateTarget.HUB, issues,
                mapOf("hub" to RelocateTarget.HUB, "resource-spawn" to RelocateTarget.RESOURCE_SPAWN),
            ),
            zone = parseZone(config, issues),
            dateFormat = parseDateFormat(config, issues),
        )
        return ParsedSettings(settings, issues)
    }

    fun hubKeyProblem(key: Key, resourceKeys: Collection<Key>): HubKeyProblem? = when (key) {
        in DEFAULT_WORLD_KEYS -> HubKeyProblem.DEFAULT_WORLD
        in resourceKeys -> HubKeyProblem.RESOURCE_WORLD
        else -> null
    }

    /** 数値はそのまま、文字列はバニラと同じく hashCode を seed にする。空なら未指定（ランダム）。 */
    fun parseSeed(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.toLongOrNull() ?: text.hashCode().toLong()
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

    private fun parseHub(
        config: ConfigurationSection,
        worlds: Map<ResourceType, WorldSettings>,
        issues: MutableList<ConfigIssue>,
    ): HubSettings {
        var delay = config.getInt("hub.teleport-delay-seconds", 0)
        if (delay < 0) {
            issues += ConfigIssue("hub.teleport-delay-seconds", "$delay は 0 以上にしてください。0 を使います")
            delay = 0
        }
        return HubSettings(
            key = parseHubKey(config.getString("hub.world"), worlds, issues),
            joinMode = parseChoice(
                config, "hub.teleport-on-join", HubJoinMode.FIRST_JOIN, issues,
                mapOf("first-join" to HubJoinMode.FIRST_JOIN, "always" to HubJoinMode.ALWAYS),
            ),
            teleportDelaySeconds = delay,
            cancelOnMove = config.getBoolean("hub.cancel-on-move", true),
            protection = HubProtection(
                blockBreak = config.getBoolean("hub.protection.block-break", true),
                blockPlace = config.getBoolean("hub.protection.block-place", true),
                pvp = config.getBoolean("hub.protection.pvp", true),
                hunger = config.getBoolean("hub.protection.hunger", true),
                fallDamage = config.getBoolean("hub.protection.fall-damage", true),
                mobSpawn = config.getBoolean("hub.protection.mob-spawn", true),
            ),
        )
    }

    private fun parseHubKey(
        raw: String?,
        worlds: Map<ResourceType, WorldSettings>,
        issues: MutableList<ConfigIssue>,
    ): Key? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val key = try {
            Key.key(text)
        } catch (_: InvalidKeyException) {
            issues += ConfigIssue("hub.world", "'$text' はワールドキーとして不正です。ハブ未設定として扱います")
            return null
        }
        return when (hubKeyProblem(key, worlds.values.map { it.key })) {
            HubKeyProblem.DEFAULT_WORLD -> {
                issues += ConfigIssue("hub.world", "'$text' はサバイバル用のデフォルトワールドです。ハブ未設定として扱います")
                null
            }
            HubKeyProblem.RESOURCE_WORLD -> {
                issues += ConfigIssue("hub.world", "'$text' は資源ワールドです。ハブ未設定として扱います")
                null
            }
            null -> key
        }
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
