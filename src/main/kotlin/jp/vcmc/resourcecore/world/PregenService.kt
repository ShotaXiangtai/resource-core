package jp.vcmc.resourcecore.world

import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.logic.borderRadius
import net.kyori.adventure.key.Key
import org.bukkit.World
import org.popcraft.chunky.ChunkyProvider
import org.popcraft.chunky.api.ChunkyAPI
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Chunky による事前生成。チャンクは Chunky がバックグラウンドで読み込むので、生成中もプレイできる。
 * Chunky が無くても動くよう、Chunky のクラスに触るのは api が用意できたときだけにする。
 */
class PregenService(private val plugin: ResourceCorePlugin) {
    private val progress = ConcurrentHashMap<String, Float>()
    private val cancelWaiters = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    @Volatile
    private var api: Any? = null

    val isAvailable: Boolean get() = api != null

    /** Chunky が有効なら接続する。 */
    fun connect() {
        if (plugin.server.pluginManager.getPlugin(CHUNKY_PLUGIN)?.isEnabled != true) {
            plugin.logger.warning("Chunky が見つからないため、事前生成は行いません")
            return
        }
        try {
            val chunky = ChunkyProvider.get().api
            chunky.onGenerationProgress { event -> progress[event.world()] = event.progress() }
            chunky.onGenerationComplete { event -> onComplete(event) }
            api = chunky
        } catch (e: LinkageError) {
            plugin.logger.severe("Chunky の API に接続できませんでした。事前生成は行いません: ${e.message}")
        } catch (e: RuntimeException) {
            plugin.logger.severe("Chunky の API に接続できませんでした。事前生成は行いません: ${e.message}")
        }
    }

    private fun onComplete(event: GenerationCompleteEvent) {
        val id = event.world()
        progress.remove(id)
        val key = runCatching { Key.key(id) }.getOrNull()
        when (event.reason()) {
            GenerationCompleteEvent.Reason.COMPLETED -> {
                plugin.logger.info("$id の事前生成が完了しました (${event.chunks()} チャンク)")
                key?.let { plugin.data.setPregenPending(it, false) }
                plugin.data.saveAsync()
            }
            GenerationCompleteEvent.Reason.CANCELLED -> {
                key?.let { plugin.data.setPregenPending(it, false) }
                plugin.data.saveAsync()
            }
            // サーバー停止や /chunky pause。pending を残して次回の起動で再開する。
            GenerationCompleteEvent.Reason.PAUSED, null -> Unit
        }
        // ループを抜けた時点では発注済みのチャンク読み込みが残っているので、少し待ってから知らせる。
        cancelWaiters.remove(id)?.let { waiter ->
            plugin.tasks.asyncDelayed(IN_FLIGHT_GRACE_SECONDS, TimeUnit.SECONDS) { waiter.complete(Unit) }
        }
    }

    /** ボーダーの範囲全体の事前生成を開始する。 */
    fun start(world: World, type: ResourceType) {
        val chunky = api as? ChunkyAPI ?: return
        if (!plugin.settings.pregenEnabled) return
        val id = world.key().asString()
        val radius = borderRadius(type, plugin.settings.borderRadius).toDouble()
        plugin.data.setPregenPending(world.key(), true)
        plugin.data.saveAsync()
        if (chunky.startTask(id, SHAPE, 0.0, 0.0, radius, radius, PATTERN)) {
            plugin.logger.info("$id の事前生成を開始しました (半径 ${radius.toInt()})")
        } else {
            plugin.logger.warning("$id の事前生成を開始できませんでした。すでに実行中の可能性があります")
        }
    }

    /** 再起動などで止まった事前生成を再開する。 */
    fun resumePending() {
        val chunky = api as? ChunkyAPI ?: return
        if (!plugin.settings.pregenEnabled) return
        ResourceType.entries.forEach { type ->
            val world = plugin.worlds.world(type) ?: return@forEach
            val id = world.key().asString()
            if (!plugin.data.isPregenPending(world.key()) || chunky.isRunning(id)) return@forEach
            if (chunky.continueTask(id)) {
                plugin.logger.info("$id の事前生成を再開しました")
            } else {
                // 途中経過が無ければ最初から。生成済みのチャンクは Chunky が飛ばす。
                start(world, type)
            }
        }
    }

    /**
     * 実行中の事前生成を止める。リセットでワールドをアンロードする前に呼ぶ。
     * 終わらないままアンロードしないよう、発注済みの読み込みが落ち着くまで待てる Future を返す。
     */
    fun cancel(key: Key): CompletableFuture<Unit> {
        val chunky = api as? ChunkyAPI ?: return CompletableFuture.completedFuture(Unit)
        val id = key.asString()
        plugin.data.setPregenPending(key, false)
        if (!chunky.isRunning(id)) return CompletableFuture.completedFuture(Unit)
        val waiter = CompletableFuture<Unit>()
        cancelWaiters[id] = waiter
        if (!chunky.cancelTask(id)) {
            cancelWaiters.remove(id)
            return CompletableFuture.completedFuture(Unit)
        }
        plugin.logger.info("$id の事前生成を止めています")
        // 完了イベントが来なくてもリセットが止まらないようにする。
        plugin.tasks.asyncDelayed(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS) {
            if (cancelWaiters.remove(id, waiter)) {
                plugin.logger.warning("$id の事前生成の停止を確認できませんでした。そのまま続行します")
                waiter.complete(Unit)
            }
        }
        return waiter
    }

    /** status と GUI に出す文字列。 */
    fun label(key: Key): String {
        val id = key.asString()
        val messages = plugin.messages
        val running = (api as? ChunkyAPI)?.isRunning(id) == true
        return when {
            !isAvailable -> messages.raw("pregen.no-chunky")
            running -> messages.raw("pregen.running")
                .replace("<percent>", String.format(Locale.ROOT, "%.1f", progress[id] ?: 0f))
            plugin.data.isPregenPending(key) -> messages.raw("pregen.waiting")
            else -> messages.raw("pregen.idle")
        }
    }

    private companion object {
        const val CHUNKY_PLUGIN = "Chunky"
        const val SHAPE = "square"
        // 中心から外へ生成する。プレイヤーが最初に行く場所から順に済む。
        const val PATTERN = "concentric"
        const val IN_FLIGHT_GRACE_SECONDS = 3L
        const val CANCEL_TIMEOUT_SECONDS = 30L
    }
}
