package jp.vcmc.resourcecore.reset

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.CountdownStep
import jp.vcmc.resourcecore.logic.CountdownTracker
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.world.WorldPhase
import jp.vcmc.resourcecore.world.rootMessage
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.thenextlvl.worlds.Backup
import net.thenextlvl.worlds.WorldOperationException
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.World
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

enum class StartResult { STARTED_COUNTDOWN, STARTED_NOW, SKIPPED_TO_NOW, ALREADY_ACTIVE, WORLD_NOT_LOADED }
enum class CancelResult { CANCELLED, NOT_ACTIVE, TOO_LATE }

/**
 * 手動リセットの一連の流れを担当する。
 * カウントダウン → 全員をメインワールドへ移動 → (バックアップ) → regenerate → スポーン確保。
 * ワールド操作はすべてグローバルリージョンで行う。
 */
class ResetManager(private val plugin: ResourceCorePlugin) {
    private enum class Stage { COUNTING, RUNNING, CANCELLED }

    private class Session(val type: ResourceType, val key: Key, val changeSeed: Boolean, val tracker: CountdownTracker) {
        val stage = AtomicReference(Stage.COUNTING)

        @Volatile
        var timer: ScheduledTask? = null

        @Volatile
        var skipCountdown = false
    }

    private val sessions = ConcurrentHashMap<ResourceType, Session>()
    private val tasks get() = plugin.tasks
    private val messages get() = plugin.messages

    /** カウントダウン中なら残り秒数を返す。 */
    fun remainingSeconds(type: ResourceType): Int? {
        val session = sessions[type] ?: return null
        if (session.stage.get() != Stage.COUNTING) return null
        return session.tracker.remainingSeconds(System.currentTimeMillis())
    }

    fun isBusy(): Boolean = sessions.isNotEmpty()

    /** どのスレッドから呼んでもよい。 */
    fun start(type: ResourceType, immediately: Boolean): StartResult {
        if (plugin.worlds.world(type) == null) return StartResult.WORLD_NOT_LOADED
        if (plugin.worlds.phase(type) == WorldPhase.RESETTING) return StartResult.ALREADY_ACTIVE

        sessions[type]?.let { existing ->
            if (immediately && existing.stage.get() == Stage.COUNTING) {
                existing.skipCountdown = true
                return StartResult.SKIPPED_TO_NOW
            }
            return StartResult.ALREADY_ACTIVE
        }

        val settings = plugin.settings
        val steps = settings.reset.countdown
        val session = Session(
            type,
            settings.world(type).key,
            settings.world(type).changeSeed,
            CountdownTracker(steps, System.currentTimeMillis()),
        )
        if (sessions.putIfAbsent(type, session) != null) return StartResult.ALREADY_ACTIVE

        if (immediately || steps.isEmpty()) {
            tasks.global { execute(session) }
            return StartResult.STARTED_NOW
        }
        session.timer = tasks.globalTimer(1, 20) { task -> tick(session, task) }
        return StartResult.STARTED_COUNTDOWN
    }

    /** どのスレッドから呼んでもよい。 */
    fun cancel(type: ResourceType): CancelResult {
        val session = sessions[type] ?: return CancelResult.NOT_ACTIVE
        if (!session.stage.compareAndSet(Stage.COUNTING, Stage.CANCELLED)) return CancelResult.TOO_LATE
        session.timer?.cancel()
        sessions.remove(type, session)
        messages.broadcast(plugin.server, messages.text("reset.cancelled", "world" to messages.worldName(type)))
        return CancelResult.CANCELLED
    }

    fun shutdown() {
        sessions.values.forEach { it.timer?.cancel() }
        sessions.clear()
    }

    private fun tick(session: Session, task: ScheduledTask) {
        if (session.stage.get() != Stage.COUNTING) {
            task.cancel()
            return
        }
        if (session.skipCountdown) {
            execute(session)
            return
        }
        val poll = session.tracker.poll(System.currentTimeMillis())
        poll.announce?.let { announce(session.type, it, poll.remainingSeconds) }
        if (poll.finished) execute(session)
    }

    private fun announce(type: ResourceType, step: CountdownStep, remaining: Int) {
        val args = arrayOf("world" to messages.worldName(type), "time" to messages.duration(remaining))
        val component = step.message?.let { messages.parse(it, *args) } ?: messages.text("reset.countdown", *args)
        messages.broadcast(plugin.server, component)
    }

    private fun execute(session: Session) {
        if (!session.stage.compareAndSet(Stage.COUNTING, Stage.RUNNING)) return
        session.timer?.cancel()
        val world = plugin.worlds.world(session.type)
        if (world == null) {
            fail(session, "ワールドがロードされていません")
            return
        }
        // ここから /resource とポータルでの移動をブロックする。
        plugin.worlds.setPhase(session.type, WorldPhase.RESETTING)
        messages.broadcast(plugin.server, messages.text("reset.started", "world" to messages.worldName(session.type)))
        plugin.logger.info("${session.key.asString()} のリセットを開始します")
        // 事前生成が動いたままだとアンロードと競合するので、先に止める。
        plugin.pregen.cancel(session.key).whenComplete { _, _ ->
            tasks.global { moveOut(session, world, attempt = 1) }
        }
    }

    /**
     * 対象ワールドの全員を、メインワールド側の元いた場所へ移動する。
     * Worlds の regenerate は呼び出し時点で中にいた人を再生成後に戻すので、必ず先に空にする。
     */
    private fun moveOut(session: Session, world: World, attempt: Int) {
        val timeoutMillis = plugin.settings.reset.moveTimeoutSeconds * 1000L
        val deadline = System.currentTimeMillis() + timeoutMillis
        val hardDeadline = deadline + KICK_GRACE_MILLIS
        val inFlight = ConcurrentHashMap.newKeySet<UUID>()
        var kicked = false
        val name = messages.worldName(session.type)

        tasks.globalTimer(1, 20) { task ->
            val players = world.players
            val now = System.currentTimeMillis()
            when {
                players.isEmpty() -> {
                    task.cancel()
                    afterMoved(session, world, attempt)
                }
                now > hardDeadline -> {
                    task.cancel()
                    fail(session, "プレイヤーを移動できませんでした: ${players.joinToString { it.name }}")
                }
                now > deadline && !kicked -> {
                    kicked = true
                    plugin.logger.warning("${session.key.asString()} から移動できなかったプレイヤーをキックします: ${players.joinToString { it.name }}")
                    players.forEach { player ->
                        tasks.entity(player) { player.kick(messages.text("reset.kicked", "world" to name)) }
                    }
                }
                else -> players.forEach { player ->
                    if (inFlight.add(player.uniqueId)) {
                        tasks.teleport(player, plugin.spawns.returnLocation(player.uniqueId)).whenComplete { success, _ ->
                            inFlight.remove(player.uniqueId)
                            if (success == true) messages.send(player, "reset.moved", "world" to name)
                        }
                    }
                }
            }
        }
    }

    private fun afterMoved(session: Session, world: World, attempt: Int) {
        val backup: CompletableFuture<Backup?> =
            if (plugin.settings.reset.backup && attempt == 1) {
                plugin.logger.info("${session.key.asString()} のバックアップを取っています")
                WorldsAccess.access().createBackup(world, null).thenApply<Backup?> { it }
            } else {
                CompletableFuture.completedFuture(null)
            }
        backup.whenComplete { result, error ->
            tasks.global {
                if (error != null) {
                    // バックアップが要る設定で失敗したのにデータを消すわけにはいかないので、中止する。
                    fail(session, "バックアップに失敗したため中止しました: ${rootMessage(error)}")
                } else {
                    if (result != null) plugin.logger.info("${session.key.asString()} のバックアップを作成しました: ${result.name()}")
                    regenerate(session, world, attempt)
                }
            }
        }
    }

    private fun regenerate(session: Session, world: World, attempt: Int) {
        val type = session.type
        val newSeed = if (session.changeSeed) ThreadLocalRandom.current().nextLong() else null
        val dragonData = world.worldPath.resolve(DRAGON_FIGHT_DATA)

        clearDragonBossBar(world)
        clearRespawnPoints(session.key)

        val resetAt = System.currentTimeMillis()
        WorldsAccess.access().regenerate(world) { builder ->
            // このブロックはアンロード成功後、ファイル削除の直前に呼ばれる。
            if (newSeed != null) builder.seed(newSeed)
            if (type == ResourceType.END) {
                // regenerate は ender_dragon_fight.dat を残す。討伐済みの記録が残るとドラゴンが出ず、
                // 帰還ポータルも生成されないので、ここで消して戦闘を初期状態に戻す。
                Files.deleteIfExists(dragonData)
            }
        }.whenComplete { regenerated, error ->
            tasks.global {
                if (error != null) {
                    onRegenerateFailed(session, world, attempt, error)
                } else {
                    onRegenerated(session, regenerated, newSeed, resetAt)
                }
            }
        }
    }

    private fun onRegenerateFailed(session: Session, world: World, attempt: Int, error: Throwable) {
        val cause = generateSequence(error) { it.cause }.filterIsInstance<WorldOperationException>().firstOrNull()
        val unloadFailed = cause?.reason() == WorldOperationException.Reason.UNLOAD_FAILED
        if (unloadFailed && attempt < MAX_ATTEMPTS && plugin.server.getWorld(session.key) != null) {
            plugin.logger.warning("${session.key.asString()} をアンロードできなかったのでやり直します ($attempt/$MAX_ATTEMPTS)")
            moveOut(session, world, attempt + 1)
            return
        }
        fail(session, rootMessage(error))
    }

    private fun onRegenerated(session: Session, world: World, newSeed: Long?, resetAt: Long) {
        if (newSeed != null && world.seed != newSeed) {
            plugin.logger.warning("${session.key.asString()} の seed が想定と異なります (期待: $newSeed, 実際: ${world.seed})")
        }
        plugin.data.setLastReset(session.key, resetAt)
        plugin.data.setSpawnPrepared(session.key, false)
        plugin.data.saveAsync()
        plugin.worlds.applyBorder(world, session.type)

        plugin.spawns.prepare(world, session.type).whenComplete { _, _ ->
            tasks.global {
                plugin.data.setSpawnPrepared(session.key, true)
                plugin.data.saveAsync()
                plugin.worlds.setPhase(session.type, WorldPhase.READY)
                plugin.worlds.validateRoutes()
                sessions.remove(session.type, session)
                plugin.logger.info("${session.key.asString()} のリセットが完了しました (seed: ${world.seed})")
                messages.broadcast(plugin.server, messages.text("reset.finished", "world" to messages.worldName(session.type)))
                // 移動のブロックを解除してから裏で生成する。生成の完了は待たない。
                plugin.pregen.start(world, session.type)
            }
        }
    }

    /** Worlds の Folia 用アンロード処理はボスバーを消さないので、残らないよう先に消す。 */
    private fun clearDragonBossBar(world: World) {
        try {
            world.enderDragonBattle?.bossBar?.removeAll()
        } catch (e: Exception) {
            plugin.logger.warning("ドラゴンのボスバーを消せませんでした: ${e.message}")
        }
    }

    /** オンラインの人の、消えるワールドにあるリスポーン地点を解除する。オフラインの人はログイン時に処理する。 */
    private fun clearRespawnPoints(key: Key) {
        plugin.server.onlinePlayers.forEach { player ->
            tasks.entity(player) {
                // Canvas が非推奨にしているのは検証ありの呼び出し。false は保存値を読むだけで安全。
                @Suppress("DEPRECATION")
                val location = player.getRespawnLocation(false)
                if (location?.world?.key() == key) {
                    player.setRespawnLocation(null)
                    messages.send(player, "login.respawn-cleared")
                }
            }
        }
    }

    private fun fail(session: Session, reason: String) {
        plugin.logger.severe("${session.key.asString()} のリセットに失敗しました: $reason")
        val phase = if (plugin.server.getWorld(session.key) != null) WorldPhase.READY else WorldPhase.MISSING
        plugin.worlds.setPhase(session.type, phase)
        sessions.remove(session.type, session)
        val component: Component = messages.text("reset.failed", "world" to messages.worldName(session.type))
        messages.broadcast(plugin.server, component)
    }

    private companion object {
        const val DRAGON_FIGHT_DATA = "data/minecraft/ender_dragon_fight.dat"
        const val KICK_GRACE_MILLIS = 10_000L
        const val MAX_ATTEMPTS = 3
    }
}
