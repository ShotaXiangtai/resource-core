package jp.vcmc.resourcecore.listener

import io.canvasmc.canvas.event.EntityPortalAsyncEvent
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.PortalKind
import jp.vcmc.resourcecore.logic.PortalRouting
import jp.vcmc.resourcecore.logic.TargetState
import org.bukkit.PortalType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * CanvasMC 専用の保険。実際にワールドを移動する直前に発火する EntityPortalAsyncEvent で、
 * 資源ワールドから出るポータルの行き先を確定させる。
 * Worlds の Issue #480 で報告された、カスタムネザーから戻るとデフォルトワールドへ飛ぶ症状への対策。
 * Canvas 以外ではクラスが無いので、ResourceCorePlugin が存在を確認してから登録する。
 */
class CanvasPortalListener(private val plugin: ResourceCorePlugin) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: EntityPortalAsyncEvent) {
        val source = plugin.worlds.typeOf(event.from.key()) ?: return
        val kind = when (event.portalType) {
            PortalType.NETHER -> PortalKind.NETHER
            PortalType.ENDER -> PortalKind.END
            else -> return
        }
        val target = PortalRouting.target(source, kind)
        val expected = plugin.worlds.world(target)
        if (expected == null || plugin.worlds.targetState(target) != TargetState.READY || plugin.worlds.isRouteBroken(source, kind)) {
            event.isCancelled = true
            return
        }
        if (event.to != expected) {
            plugin.logger.warning("ポータルの行き先を補正しました: ${event.to.key().asString()} → ${expected.key().asString()}")
            event.to = expected
        }
    }
}
