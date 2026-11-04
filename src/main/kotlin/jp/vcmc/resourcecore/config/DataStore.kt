package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.util.Tasks
import jp.vcmc.resourcecore.util.writeAtomically
import net.kyori.adventure.key.Key
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** ハブのゲームルールをいじる前の値の記録。OFF にしたときに元へ戻すために持つ。 */
data class HubRuleRecord(val world: String, val originals: Map<String, Boolean>)

/** data.yml に保存する実行時データ。どのスレッドから読み書きしてもよい。 */
class DataStore(private val file: File, private val logger: Logger, private val tasks: Tasks) {
    private val lastResets = ConcurrentHashMap<String, Long>()
    private val spawnPrepared = ConcurrentHashMap.newKeySet<String>()
    private val writeLock = Any()

    @Volatile
    var hubRules: HubRuleRecord? = null

    fun load() {
        if (!file.exists()) return
        val yaml = newYaml()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            logger.severe("data.yml を読み込めませんでした。リセット履歴なしで続行します: ${e.message}")
            return
        }
        yaml.getConfigurationSection("last-reset")?.let { section ->
            section.getKeys(false).forEach { lastResets[it] = section.getLong(it) }
        }
        spawnPrepared += yaml.getStringList("spawn-prepared")
        val hubWorld = yaml.getString("hub-rules|world")
        if (hubWorld != null) {
            val originals = yaml.getConfigurationSection("hub-rules|originals")?.let { section ->
                section.getKeys(false).associateWith { section.getBoolean(it) }
            }.orEmpty()
            hubRules = HubRuleRecord(hubWorld, originals)
        }
    }

    fun lastReset(key: Key): Long? = lastResets[key.asString()]

    fun setLastReset(key: Key, millis: Long) {
        lastResets[key.asString()] = millis
    }

    fun isSpawnPrepared(key: Key): Boolean = key.asString() in spawnPrepared

    fun setSpawnPrepared(key: Key, prepared: Boolean) {
        if (prepared) spawnPrepared += key.asString() else spawnPrepared -= key.asString()
    }

    fun saveAsync() = tasks.async { saveNow() }

    fun saveNow() {
        val yaml = newYaml()
        lastResets.forEach { (key, value) -> yaml.set("last-reset|$key", value) }
        yaml.set("spawn-prepared", spawnPrepared.sorted())
        hubRules?.let { record ->
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
