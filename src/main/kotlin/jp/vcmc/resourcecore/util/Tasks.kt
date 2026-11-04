package jp.vcmc.resourcecore.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.plugin.Plugin
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** リージョンスレッド環境のスケジューラの薄いラッパー。Bukkit.getScheduler() は使わない。 */
class Tasks(private val plugin: Plugin) {
    private val server get() = plugin.server

    /** グローバルリージョンで実行する。ワールドの作成、再生成、ゲームルールの変更はここで行う。 */
    fun global(block: () -> Unit) {
        if (!plugin.isEnabled) return
        server.globalRegionScheduler.execute(plugin, Runnable(block))
    }

    fun globalTimer(initialDelayTicks: Long, periodTicks: Long, block: (ScheduledTask) -> Unit): ScheduledTask =
        server.globalRegionScheduler.runAtFixedRate(plugin, { block(it) }, initialDelayTicks, periodTicks)

    /** ファイル I/O 用。 */
    fun async(block: () -> Unit) {
        if (!plugin.isEnabled) {
            block()
            return
        }
        server.asyncScheduler.runNow(plugin) { block() }
    }

    fun asyncDelayed(delay: Long, unit: TimeUnit, block: () -> Unit) {
        server.asyncScheduler.runDelayed(plugin, { block() }, delay, unit)
    }

    /** そのチャンクを所有するリージョンで実行する。ブロック操作はここで行う。 */
    fun region(world: World, chunkX: Int, chunkZ: Int, block: () -> Unit) {
        server.regionScheduler.execute(plugin, world, chunkX, chunkZ, Runnable(block))
    }

    /** エンティティを所有するリージョンで実行する。削除済みなら retired を呼ぶ。 */
    fun entity(entity: Entity, retired: () -> Unit = {}, block: () -> Unit) {
        val scheduled = entity.scheduler.run(plugin, { block() }, Runnable(retired))
        if (scheduled == null) retired()
    }

    fun entityTimer(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        retired: () -> Unit = {},
        block: (ScheduledTask) -> Unit,
    ): ScheduledTask? =
        entity.scheduler.runAtFixedRate(plugin, { block(it) }, Runnable(retired), initialDelayTicks, periodTicks)

    /** プレイヤーを所有リージョンで乗り物から降ろして teleportAsync する。 */
    fun teleport(player: Player, location: Location, cause: TeleportCause = TeleportCause.PLUGIN): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        entity(player, retired = { result.complete(false) }) {
            player.leaveVehicle()
            player.teleportAsync(location, cause).whenComplete { success, error ->
                result.complete(error == null && success == true)
            }
        }
        return result
    }
}

/** 途中で落ちてもファイルが壊れないよう、一時ファイルに書いてから置き換える。 */
fun writeAtomically(target: Path, content: String) {
    target.parent?.let { Files.createDirectories(it) }
    val temp = target.resolveSibling("${target.fileName}.tmp")
    Files.writeString(temp, content, Charsets.UTF_8)
    try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
