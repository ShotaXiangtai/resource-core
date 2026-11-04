package jp.vcmc.resourcecore.listener

import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.PortalAction
import jp.vcmc.resourcecore.logic.PortalKind
import jp.vcmc.resourcecore.logic.PortalRouting
import jp.vcmc.resourcecore.logic.ResourceType
import org.bukkit.PortalType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 資源ワールドのポータルを処理する。サバイバル側のイベントには何もしない。
 *
 * 行き先の解決は Worlds が同じ namespace で自動的に行う。ここで手を入れるのは次の 2 点だけ。
 * - 行き先がリセット中や利用不可のときに止める
 * - エンドからの帰還を、ベッドではなく資源オーバーワールドへ送る
 *
 * Worlds のリスナーは HIGH かつ ignoreCancelled なので、ここでキャンセルすれば Worlds は動かない。
 */
class PortalListener(private val plugin: ResourceCorePlugin) : Listener {
    private val returning = ConcurrentHashMap.newKeySet<UUID>()
    private val lastNotice = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPortalEnter(event: EntityPortalEnterEvent) {
        val entity = event.entity
        val source = plugin.worlds.typeOf(entity.world.key()) ?: return
        val kind = when (event.portalType) {
            PortalType.NETHER -> PortalKind.NETHER
            PortalType.ENDER -> PortalKind.END
            else -> return
        }
        val target = PortalRouting.target(source, kind)
        val action = PortalRouting.decide(
            source = source,
            kind = kind,
            isPlayer = entity is Player,
            targetState = plugin.worlds.targetState(target),
            routeBroken = plugin.worlds.isRouteBroken(source, kind),
        )
        when (action) {
            PortalAction.PASS -> Unit
            PortalAction.BLOCK_RESETTING -> {
                event.isCancelled = true
                notify(entity, "resource.portal-resetting", target)
            }
            PortalAction.BLOCK_UNAVAILABLE -> {
                event.isCancelled = true
                notify(entity, "resource.portal-unavailable", target)
            }
            PortalAction.RETURN_TO_OVERWORLD -> {
                event.isCancelled = true
                returnToOverworld(entity as Player)
            }
        }
    }

    private fun returnToOverworld(player: Player) {
        val world = plugin.worlds.world(ResourceType.OVERWORLD) ?: return
        // イベントはポータルに触れている間ずっと発火するので、移動中は多重に送らない。
        if (!returning.add(player.uniqueId)) return
        plugin.tasks.teleport(player, plugin.spawns.spawnOf(world, ResourceType.OVERWORLD), TeleportCause.END_PORTAL)
            .whenComplete { _, _ -> returning.remove(player.uniqueId) }
    }

    private fun notify(entity: org.bukkit.entity.Entity, path: String, target: ResourceType) {
        if (entity !is Player) return
        val now = System.currentTimeMillis()
        val previous = lastNotice[entity.uniqueId]
        if (previous != null && now - previous < NOTICE_INTERVAL_MILLIS) return
        lastNotice[entity.uniqueId] = now
        plugin.messages.send(entity, path, "world" to plugin.messages.worldName(target))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastNotice.remove(event.player.uniqueId)
        returning.remove(event.player.uniqueId)
    }

    private companion object {
        const val NOTICE_INTERVAL_MILLIS = 3000L
    }
}
