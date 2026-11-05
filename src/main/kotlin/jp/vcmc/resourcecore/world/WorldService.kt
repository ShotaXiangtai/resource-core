package jp.vcmc.resourcecore.world

import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.PortalKind
import jp.vcmc.resourcecore.logic.PortalRouting
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.logic.TargetState
import jp.vcmc.resourcecore.logic.borderRadius
import net.kyori.adventure.key.Key
import net.thenextlvl.worlds.Dimension
import net.thenextlvl.worlds.Level
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.PortalType
import org.bukkit.World
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class WorldPhase { MISSING, CREATING, READY, RESETTING }

/** 資源ワールドの作成とロード、状態、ポータル連携の検証を受け持つ。 */
class WorldService(private val plugin: ResourceCorePlugin) {
    private val phases = ConcurrentHashMap<ResourceType, WorldPhase>()

    @Volatile
    private var brokenRoutes: Set<Pair<ResourceType, PortalKind>> = emptySet()

    fun key(type: ResourceType): Key = plugin.settings.world(type).key

    fun world(type: ResourceType): World? = plugin.server.getWorld(key(type))

    fun phase(type: ResourceType): WorldPhase {
        val stored = phases[type]
        // /world unload などで外部からアンロードされた場合もあるので、実際の状態を優先する。
        if (stored == null || stored == WorldPhase.READY || stored == WorldPhase.MISSING) {
            return if (world(type) != null) WorldPhase.READY else WorldPhase.MISSING
        }
        return stored
    }

    fun setPhase(type: ResourceType, phase: WorldPhase) {
        phases[type] = phase
    }

    fun typeOf(key: Key): ResourceType? = plugin.settings.typeOf(key)

    fun targetState(type: ResourceType): TargetState = when {
        phase(type) == WorldPhase.RESETTING -> TargetState.RESETTING
        phase(type) == WorldPhase.READY && world(type) != null -> TargetState.READY
        else -> TargetState.UNAVAILABLE
    }

    fun isRouteBroken(source: ResourceType, kind: PortalKind): Boolean = (source to kind) in brokenRoutes

    /** 全ワールドを順番に用意してからポータル連携を検証する。グローバルリージョンから呼ぶこと。 */
    fun ensureAll(): CompletableFuture<Unit> {
        var chain = CompletableFuture.completedFuture(Unit)
        for (type in ResourceType.entries) {
            chain = chain.thenCompose {
                val next = CompletableFuture<Unit>()
                ensure(type).whenComplete { _, _ -> next.complete(Unit) }
                next
            }
        }
        return chain.thenApply {
            plugin.tasks.global {
                validateRoutes()
                plugin.pregen.resumePending()
            }
        }
    }

    /** ワールドボーダーを設定する。ネザーは範囲を揃えるため半径を 8 分の 1 にする。 */
    fun applyBorder(world: World, type: ResourceType) {
        plugin.tasks.global {
            val border = world.worldBorder
            border.setCenter(0.0, 0.0)
            border.size = borderRadius(type, plugin.settings.borderRadius) * 2.0
        }
    }

    /**
     * ワールドが無ければ Worlds API で作成する。グローバルリージョンから呼ぶこと。
     * 失敗してもログに残すだけで、管理 GUI やコマンドからやり直せる。
     */
    fun ensure(type: ResourceType): CompletableFuture<World> {
        val settings = plugin.settings.world(type)
        val loaded = world(type)
        if (loaded != null) {
            if (phases[type] != WorldPhase.RESETTING) phases[type] = WorldPhase.CREATING
            applyBorder(loaded, type)
            return prepareSpawnIfNeeded(loaded, type).thenApply {
                if (phases[type] == WorldPhase.CREATING) phases[type] = WorldPhase.READY
                loaded
            }
        }
        if (phases[type] == WorldPhase.CREATING || phases[type] == WorldPhase.RESETTING) {
            return CompletableFuture.failedFuture(IllegalStateException("${settings.key.asString()} は処理中です"))
        }
        phases[type] = WorldPhase.CREATING

        val access = WorldsAccess.access()
        val isNew = !access.worldRegistry.isRegistered(settings.key)
        val future: CompletableFuture<World> = try {
            if (!isNew) {
                plugin.logger.info("${settings.key.asString()} をロードします")
                access.load(settings.key)
            } else {
                plugin.logger.info("${settings.key.asString()} を作成します")
                // 同じキーで作り直す場合、以前のワールドで記録した場所は使えない。
                plugin.data.clearResourceLocations(settings.key)
                val dimension = dimensionOf(type)
                val level = Level.builder(settings.key)
                    .dimension(dimension)
                    .seed(settings.seed)
                    .build()
                level.create().thenApply { created ->
                    // create() は登録まで行わないので、再起動後もロードされるよう登録する。
                    access.worldRegistry.registerIfAbsent(settings.key, dimension, true, null)
                    created
                }
            }
        } catch (e: Exception) {
            CompletableFuture.failedFuture(e)
        }

        return future
            .thenCompose { created ->
                applyBorder(created, type)
                prepareSpawnIfNeeded(created, type).thenApply { created }
            }
            .whenComplete { created, error ->
                if (error != null) {
                    plugin.logger.severe("${settings.key.asString()} を用意できませんでした: ${rootMessage(error)}")
                    phases[type] = WorldPhase.MISSING
                } else {
                    phases[type] = WorldPhase.READY
                    if (isNew) plugin.tasks.global { plugin.pregen.start(created, type) }
                }
            }
    }

    private fun prepareSpawnIfNeeded(world: World, type: ResourceType): CompletableFuture<Unit> {
        if (plugin.data.isSpawnPrepared(world.key())) return CompletableFuture.completedFuture(Unit)
        return plugin.spawns.prepare(world, type).thenApply {
            plugin.data.setSpawnPrepared(world.key(), true)
            plugin.data.saveAsync()
        }
    }

    /**
     * Worlds は同じ namespace の Environment でポータルの行き先を決める。設定次第では意図と違う
     * ワールドへ飛ぶので、実際の解決結果を確認し、異常があればその経路のポータルを止める。
     */
    fun validateRoutes() {
        val access = WorldsAccess.access()
        val broken = mutableSetOf<Pair<ResourceType, PortalKind>>()
        for ((source, kind) in ROUTES) {
            val sourceWorld = world(source) ?: continue
            val target = PortalRouting.target(source, kind)
            val expected = world(target) ?: continue
            val portalType = if (kind == PortalKind.NETHER) PortalType.NETHER else PortalType.ENDER
            val actual = access.getPortalTarget(sourceWorld, portalType).orElse(null)
            if (actual != expected) {
                broken += source to kind
                plugin.logger.severe(
                    "ポータル連携が想定と異なるため無効にします: ${sourceWorld.key().asString()} の ${kind.name} ポータル " +
                        "(期待: ${expected.key().asString()}, 実際: ${actual?.key()?.asString() ?: "なし"})。" +
                        "資源ワールドのキーを同じ namespace にし、その namespace に他のワールドを置かないでください",
                )
            }
        }
        brokenRoutes = broken
    }

    private fun dimensionOf(type: ResourceType): Dimension = when (type) {
        ResourceType.OVERWORLD -> Dimension.OVERWORLD
        ResourceType.NETHER -> Dimension.THE_NETHER
        ResourceType.END -> Dimension.THE_END
    }

    private companion object {
        val ROUTES = listOf(
            ResourceType.OVERWORLD to PortalKind.NETHER,
            ResourceType.OVERWORLD to PortalKind.END,
            ResourceType.NETHER to PortalKind.NETHER,
            ResourceType.END to PortalKind.END,
        )
    }
}

fun rootMessage(error: Throwable): String {
    var current = error
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return "${current.javaClass.simpleName}: ${current.message}"
}
