package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.util.writeAtomically
import net.kyori.adventure.key.Key
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** 1.0.0 のハブがゲームルールを変更する前の値。元に戻すまで保持する。 */
data class HubRuleRecord(val world: String, val originals: Map<String, Boolean>)

/** 記録しておく場所。資源ワールドへ入る直前の場所と、資源ワールドを離れる直前の場所に使う。 */
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

    /** プレイヤーごとに、ワールドキー → そのワールドを離れる直前にいた場所。 */
    private val resourceLocations = ConcurrentHashMap<UUID, ConcurrentHashMap<String, SavedLocation>>()
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
            section.getKeys(false).forEach { id ->
                val uuid = parseUuid(id) ?: return@forEach
                parseLocation(section.getConfigurationSection(id))?.let { returnLocations[uuid] = it }
            }
        }
        yaml.getConfigurationSection("resource-locations")?.let { players ->
            players.getKeys(false).forEach { id ->
                val uuid = parseUuid(id) ?: return@forEach
                val worlds = players.getConfigurationSection(id) ?: return@forEach
                worlds.getKeys(false).forEach { world ->
                    parseLocation(worlds.getConfigurationSection(world))?.let { setResourceLocation(uuid, it) }
                }
            }
        }
        val hubWorld = yaml.getString("hub-rules|world")
        if (hubWorld != null) {
            val originals = yaml.getConfigurationSection("hub-rules|originals")?.let { section ->
                section.getKeys(false).associateWith { section.getBoolean(it) }
            }.orEmpty()
            legacyHubRules = HubRuleRecord(hubWorld, originals)
        }
    }

    private fun parseUuid(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    private fun parseLocation(section: ConfigurationSection?): SavedLocation? {
        val world = section?.getString("world") ?: return null
        return SavedLocation(
            world,
            section.getDouble("x"),
            section.getDouble("y"),
            section.getDouble("z"),
            section.getDouble("yaw").toFloat(),
            section.getDouble("pitch").toFloat(),
        )
    }

    private fun writeLocation(yaml: YamlConfiguration, base: String, location: SavedLocation) {
        yaml.set("$base|world", location.world)
        yaml.set("$base|x", location.x)
        yaml.set("$base|y", location.y)
        yaml.set("$base|z", location.z)
        yaml.set("$base|yaw", location.yaw.toDouble())
        yaml.set("$base|pitch", location.pitch.toDouble())
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

    fun resourceLocation(player: UUID, world: Key): SavedLocation? = resourceLocations[player]?.get(world.asString())

    fun setResourceLocation(player: UUID, location: SavedLocation) {
        resourceLocations.computeIfAbsent(player) { ConcurrentHashMap() }[location.world] = location
    }

    fun clearResourceLocation(player: UUID, world: Key) {
        resourceLocations[player]?.remove(world.asString())
    }

    /** ワールドがリセットされたら、全員分のそのワールドの記録を捨てる。地形が変わっているので使えない。 */
    fun clearResourceLocations(world: Key) {
        resourceLocations.values.forEach { it.remove(world.asString()) }
    }

    fun saveAsync() = runAsync { saveNow() }

    fun saveNow() {
        // 保存が重なったときに古い内容で上書きしないよう、内容を作るところからロックする。
        synchronized(writeLock) {
            val yaml = newYaml()
            lastResets.forEach { (key, value) -> yaml.set("last-reset|$key", value) }
            yaml.set("spawn-prepared", spawnPrepared.sorted())
            yaml.set("pregen-pending", pregenPending.sorted())
            returnLocations.forEach { (id, location) -> writeLocation(yaml, "return-locations|$id", location) }
            resourceLocations.forEach { (id, worlds) ->
                worlds.forEach { (world, location) -> writeLocation(yaml, "resource-locations|$id|$world", location) }
            }
            legacyHubRules?.let { record ->
                yaml.set("hub-rules|world", record.world)
                record.originals.forEach { (rule, value) -> yaml.set("hub-rules|originals|$rule", value) }
            }
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
