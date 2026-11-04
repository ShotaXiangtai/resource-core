package jp.vcmc.resourcecore.listener

import jp.vcmc.resourcecore.ResourceCorePlugin
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.world.WorldLoadEvent

/** ゲームルールで足りない保護（ブロック・空腹・スポナーなどの Mob 湧き）をイベントで行う。 */
class HubProtectionListener(private val plugin: ResourceCorePlugin) : Listener {
    private fun isHub(world: World): Boolean = plugin.settings.hub.key == world.key()
    private val protection get() = plugin.settings.hub.protection

    private fun guardBlock(event: Cancellable, player: Player, world: World, enabled: Boolean) {
        if (enabled && isHub(world) && !player.hasPermission(BYPASS)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onBreak(event: BlockBreakEvent) =
        guardBlock(event, event.player, event.block.world, protection.blockBreak)

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onPlace(event: BlockPlaceEvent) =
        guardBlock(event, event.player, event.block.world, protection.blockPlace)

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) =
        guardBlock(event, event.player, event.block.world, protection.blockPlace)

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onBucketFill(event: PlayerBucketFillEvent) =
        guardBlock(event, event.player, event.block.world, protection.blockBreak)

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onFood(event: FoodLevelChangeEvent) {
        if (!protection.hunger || !isHub(event.entity.world)) return
        // 空腹度が減るときだけ止める。回復は許可する。
        if (event.foodLevel < event.entity.foodLevel) event.isCancelled = true
    }

    /** spawn_mobs ゲームルールでは止まらないスポナーなども止める。コマンドやプラグインによる召喚は許可する。 */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    fun onSpawn(event: CreatureSpawnEvent) {
        if (!protection.mobSpawn || !isHub(event.location.world)) return
        if (event.spawnReason !in ALLOWED_SPAWN_REASONS) event.isCancelled = true
    }

    /** ハブのワールドが後からロードされた場合にゲームルールを適用する。 */
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (isHub(event.world)) plugin.hub.applyProtection()
    }

    private companion object {
        const val BYPASS = "resourcecore.hub.bypass"
        val ALLOWED_SPAWN_REASONS = setOf(
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.DEFAULT,
        )
    }
}
