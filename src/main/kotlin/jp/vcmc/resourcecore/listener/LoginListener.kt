package jp.vcmc.resourcecore.listener

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.LoginContext
import jp.vcmc.resourcecore.logic.LoginDecision
import jp.vcmc.resourcecore.logic.LoginPolicy
import jp.vcmc.resourcecore.logic.TargetState
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ログイン時の移動先を決める。AsyncPlayerSpawnLocationEvent はプレイヤーがワールドに入る前に発火するので、
 * ログイン後に再テレポートせずに済む。
 */
class LoginListener(private val plugin: ResourceCorePlugin) : Listener {
    private data class PendingLogin(val lastSeenMillis: Long, val relocated: Boolean, val newPlayer: Boolean)

    private val pending = ConcurrentHashMap<UUID, PendingLogin>()

    @EventHandler(priority = EventPriority.NORMAL)
    fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val uuid = event.connection.profile.id ?: return
        val key = event.spawnLocation.world?.key()
        val type = key?.let { plugin.worlds.typeOf(it) }
        // ログイン前に取る。ログイン後は getLastSeen が現在時刻を返すようになる。
        // 読めなかった場合は移動させない側に倒す（誤って移動させるより、その場に残すほうが害が小さい）。
        val lastSeen = try {
            plugin.server.getOfflinePlayer(uuid).lastSeen
        } catch (e: Exception) {
            plugin.logger.warning("$uuid の最終ログイン時刻を読めませんでした: ${e.message}")
            Long.MAX_VALUE
        }
        val state = type?.let { plugin.worlds.targetState(it) }

        val decision = LoginPolicy.decide(
            LoginContext(
                inResourceWorld = type != null,
                worldResetting = state == TargetState.RESETTING,
                worldReady = state == TargetState.READY,
                lastSeenMillis = lastSeen,
                lastResetMillis = key?.let { plugin.data.lastReset(it) },
                relocateTarget = plugin.settings.relocateTarget,
            ),
        )
        when (decision) {
            LoginDecision.STAY -> Unit
            LoginDecision.TO_MAIN_WORLD -> event.spawnLocation = plugin.spawns.returnLocation(uuid)
            LoginDecision.TO_RESOURCE_SPAWN -> {
                val world = type?.let { plugin.worlds.world(it) }
                event.spawnLocation = if (world != null) plugin.spawns.spawnOf(world, type) else plugin.spawns.returnLocation(uuid)
            }
        }
        pending[uuid] = PendingLogin(lastSeen, decision != LoginDecision.STAY, event.isNewPlayer)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val login = pending.remove(player.uniqueId) ?: return
        if (login.relocated) plugin.messages.send(player, "login.relocated")
        if (login.newPlayer) return

        // オフラインの間に消えたワールドのリスポーン地点を解除する。ベッドが無ければ Folia / Canvas は
        // minecraft:overworld のスポーンへ戻すが、古い地点を残さないよう明示的に消す。
        // Canvas が非推奨にしているのは検証ありの呼び出し。false は保存値を読むだけで安全。
        @Suppress("DEPRECATION")
        val respawn = player.getRespawnLocation(false) ?: return
        val key = respawn.world?.key() ?: return
        if (plugin.worlds.typeOf(key) == null) return
        if (LoginPolicy.isStale(login.lastSeenMillis, plugin.data.lastReset(key))) {
            player.setRespawnLocation(null)
            plugin.messages.send(player, "login.respawn-cleared")
        }
    }
}
