package jp.vcmc.resourcecore.hub

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.config.HubRuleRecord
import jp.vcmc.resourcecore.config.HubProtection
import jp.vcmc.resourcecore.config.SettingsParser
import org.bukkit.GameRule
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** ハブへの移動、ハブの設定、ゲームルールでの保護を受け持つ。 */
class HubService(private val plugin: ResourceCorePlugin) {
    private val pending = ConcurrentHashMap<UUID, ScheduledTask>()
    private val messages get() = plugin.messages

    fun world(): World? = plugin.settings.hub.key?.let { plugin.server.getWorld(it) }

    fun spawn(): Location? = world()?.let { hub ->
        val spawn = hub.spawnLocation
        Location(hub, spawn.blockX + 0.5, spawn.blockY.toDouble(), spawn.blockZ + 0.5, spawn.yaw, spawn.pitch)
    }

    /** /hub の本体。引数なし・確認なしで実行する。待機時間は config で設定する（既定 0）。 */
    fun requestTeleport(player: Player) {
        val settings = plugin.settings.hub
        if (settings.key == null) {
            messages.send(player, "hub.not-set")
            return
        }
        if (spawn() == null) {
            messages.send(player, "hub.not-loaded")
            return
        }
        val delay = settings.teleportDelaySeconds
        if (delay <= 0) {
            teleport(player)
            return
        }
        if (pending.containsKey(player.uniqueId)) {
            messages.send(player, "hub.already-waiting")
            return
        }
        startWaiting(player, delay, settings.cancelOnMove)
    }

    private fun startWaiting(player: Player, delaySeconds: Int, cancelOnMove: Boolean) {
        val origin = player.location
        val finishAt = System.currentTimeMillis() + delaySeconds * 1000L
        val task = plugin.tasks.entityTimer(
            player,
            initialDelayTicks = 1,
            periodTicks = 5,
            retired = { pending.remove(player.uniqueId) },
        ) { task ->
            val now = player.location
            val moved = now.world != origin.world || now.blockX != origin.blockX ||
                now.blockY != origin.blockY || now.blockZ != origin.blockZ
            when {
                cancelOnMove && moved -> {
                    task.cancel()
                    pending.remove(player.uniqueId)
                    messages.send(player, "hub.cancelled-moved")
                }
                System.currentTimeMillis() >= finishAt -> {
                    task.cancel()
                    pending.remove(player.uniqueId)
                    teleport(player)
                }
            }
        } ?: return
        pending[player.uniqueId] = task
        val hint = if (cancelOnMove) messages.raw("hub.waiting-cancel-hint") else ""
        messages.send(player, "hub.waiting", "time" to messages.duration(delaySeconds), "cancel_hint" to hint)
    }

    private fun teleport(player: Player) {
        val target = spawn()
        if (target == null) {
            messages.send(player, "hub.not-loaded")
            return
        }
        plugin.tasks.teleport(player, target).thenAccept { success ->
            messages.send(player, if (success) "hub.teleporting" else "resource.teleport-failed")
        }
    }

    /** 今いるワールドをハブに設定する。config.yml に書き戻す。 */
    fun setHub(sender: CommandSender, world: World) {
        val resourceKeys = plugin.settings.worlds.values.map { it.key }
        when (SettingsParser.hubKeyProblem(world.key(), resourceKeys)) {
            SettingsParser.HubKeyProblem.DEFAULT_WORLD -> messages.send(sender, "hub.set-default-world")
            SettingsParser.HubKeyProblem.RESOURCE_WORLD -> messages.send(sender, "hub.set-resource-world")
            null -> {
                plugin.updateConfig("hub.world", world.key().asString())
                applyProtection()
                messages.send(sender, "hub.set", "world" to world.key().asString())
            }
        }
    }

    /**
     * ゲームルールで足りる保護（PvP・落下ダメージ・Mob 湧き）をハブのワールドに適用する。
     * 元の値を data.yml に残し、OFF にしたときやハブを変えたときに戻す。
     */
    fun applyProtection() {
        plugin.tasks.global {
            val settings = plugin.settings.hub
            val hubKey = settings.key?.asString()
            val record = plugin.data.hubRules
            if (record != null && record.world != hubKey) restore(record)

            val hub = world()
            if (hub == null) {
                if (hubKey != null) plugin.logger.warning("ハブのワールド $hubKey が読み込まれていないため、ゲームルールを適用できません")
                return@global
            }
            val originals = plugin.data.hubRules?.originals.orEmpty().toMutableMap()
            for ((rule, enabled) in protectionRules(settings.protection)) {
                val name = rule.key().asString()
                if (enabled) {
                    if (name !in originals) originals[name] = hub.getGameRuleValue(rule)
                    hub.setGameRule(rule, false)
                } else {
                    originals.remove(name)?.let { hub.setGameRule(rule, it) }
                }
            }
            plugin.data.hubRules = if (originals.isEmpty()) null else HubRuleRecord(hub.key().asString(), originals)
            plugin.data.saveAsync()
        }
    }

    private fun restore(record: HubRuleRecord) {
        val key = runCatching { net.kyori.adventure.key.Key.key(record.world) }.getOrNull()
        val previous = key?.let { plugin.server.getWorld(it) }
        if (previous == null) {
            plugin.logger.warning("以前のハブ ${record.world} が読み込まれていないため、ゲームルールを元に戻せませんでした: ${record.originals}")
        } else {
            for ((rule, _) in ALL_RULES) {
                record.originals[rule.key().asString()]?.let { previous.setGameRule(rule, it) }
            }
        }
        plugin.data.hubRules = null
    }

    private fun protectionRules(protection: HubProtection): List<Pair<GameRule<Boolean>, Boolean>> =
        ALL_RULES.map { (rule, enabled) -> rule to enabled(protection) }

    fun shutdown() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private companion object {
        val ALL_RULES: List<Pair<GameRule<Boolean>, (HubProtection) -> Boolean>> = listOf(
            GameRules.PVP to { it.pvp },
            GameRules.FALL_DAMAGE to { it.fallDamage },
            GameRules.SPAWN_MOBS to { it.mobSpawn },
            GameRules.SPAWN_MONSTERS to { it.mobSpawn },
        )
    }
}
