package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.util.writeAtomically
import net.kyori.adventure.key.Key
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** 1.0.0 のハブがゲームルールを変更する前の値。元に戻すまで保持する。 */
data class HubRuleRecord(val world: String, val originals: Map<String, Boolean>)

/** 資源ワールドへ入る直前にいた場所。 */
data class SavedLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

/**
 * data.yml に保存する実行時データ。どのスレッドから読み書きしてもよい。
 * runAsync には保存処理を非同期で動かす関数を渡す。
 */
class DataStore(
    private val file: File,
    private val logger: Logger,
    private val runAsync: (() -> Unit) -> Unit,
) {
    private val lastResets = ConcurrentHashMap<String, Long>()
    private val spawnPrepared = ConcurrentHashMap.newKeySet<String>()
    private val pregenPending = ConcurrentHashMap.newKeySet<String>()
    private val returnLocations = ConcurrentHashMap<UUID, SavedLocation>()
    private val writeLock = Any()

    @Volatile
    var legacyHubRules: HubRuleRecord? = null

    fun load() {
        if (!file.exists()) return
        val yaml = newYaml()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            logger.severe("data.yml を読み込めませんでした。履歴なしで続行します: ${e.message}")
            return
        }
        yaml.getConfigurationSection("last-reset")?.let { section ->
            section.getKeys(false).forEach { lastResets[it] = section.getLong(it) }
        }
        spawnPrepared += yaml.getStringList("spawn-prepared")
        pregenPending += yaml.getStringList("pregen-pending")
        yaml.getConfigurationSection("return-locations")?.let { section ->
            section.getKeys(false).forEach { id -> readLocation(section.getConfigurationSection(id), id) }
        }
        val hubWorld = yaml.getString("hub-rules|world")
        if (hubWorld != null) {
            val originals = yaml.getConfigurationSection("hub-rules|originals")?.let { section ->
                section.getKeys(false).associateWith { section.getBoolean(it) }
            }.orEmpty()
            legacyHubRules = HubRuleRecord(hubWorld, originals)
        }
    }

    private fun readLocation(section: org.bukkit.configuration.ConfigurationSection?, id: String) {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        val world = section?.getString("world") ?: return
        returnLocations[uuid] = SavedLocation(
            world,
            section.getDouble("x"),
            section.getDouble("y"),
            section.getDouble("z"),
            section.getDouble("yaw").toFloat(),
            section.getDouble("pitch").toFloat(),
        )
    }

    fun lastReset(key: Key): Long? = lastResets[key.asString()]

    fun setLastReset(key: Key, millis: Long) {
        lastResets[key.asString()] = millis
    }

    fun isSpawnPrepared(key: Key): Boolean = key.asString() in spawnPrepared

    fun setSpawnPrepared(key: Key, prepared: Boolean) {
        if (prepared) spawnPrepared += key.asString() else spawnPrepared -= key.asString()
    }

    fun isPregenPending(key: Key): Boolean = key.asString() in pregenPending

    fun setPregenPending(key: Key, pending: Boolean) {
        if (pending) pregenPending += key.asString() else pregenPending -= key.asString()
    }

    fun returnLocation(player: UUID): SavedLocation? = returnLocations[player]

    fun setReturnLocation(player: UUID, location: SavedLocation) {
        returnLocations[player] = location
    }

    fun saveAsync() = runAsync { saveNow() }

    fun saveNow() {
        val yaml = newYaml()
        lastResets.forEach { (key, value) -> yaml.set("last-reset|$key", value) }
        yaml.set("spawn-prepared", spawnPrepared.sorted())
        yaml.set("pregen-pending", pregenPending.sorted())
        returnLocations.forEach { (id, location) ->
            val base = "return-locations|$id|"
            yaml.set("${base}world", location.world)
            yaml.set("${base}x", location.x)
            yaml.set("${base}y", location.y)
            yaml.set("${base}z", location.z)
            yaml.set("${base}yaw", location.yaw.toDouble())
            yaml.set("${base}pitch", location.pitch.toDouble())
        }
        legacyHubRules?.let { record ->
            yaml.set("hub-rules|world", record.world)
            record.originals.forEach { (rule, value) -> yaml.set("hub-rules|originals|$rule", value) }
        }
        synchronized(writeLock) {
            try {
                writeAtomically(file.toPath(), yaml.saveToString())
            } catch (e: Exception) {
                logger.severe("data.yml を保存できませんでした: ${e.message}")
            }
        }
    }

    /** ワールドキーに '.' が入ることがあるので、パス区切りを変える。 */
    private fun newYaml() = YamlConfiguration().apply { options().pathSeparator('|') }
}
