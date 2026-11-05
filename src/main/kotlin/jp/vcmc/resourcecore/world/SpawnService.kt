package jp.vcmc.resourcecore.world

import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.config.SavedLocation
import jp.vcmc.resourcecore.logic.BlockKind
import jp.vcmc.resourcecore.logic.FootingKind
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.logic.SafeSpawn
import jp.vcmc.resourcecore.logic.TargetState
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.floor
import kotlin.math.min

/** スポーン地点の確保と、スポーンへのテレポートを受け持つ。 */
class SpawnService(private val plugin: ResourceCorePlugin) {
    private val tasks get() = plugin.tasks

    /** メインワールド（minecraft:overworld）のスポーン。読み取るだけで、変更はしない。 */
    private fun mainWorldSpawn(): Location {
        val overworld = plugin.server.getWorld(MAIN_WORLD) ?: plugin.server.worlds.first()
        return centered(overworld.spawnLocation)
    }

    /**
     * メインワールド側へ戻すときの行き先。資源ワールドへ入る直前にいた場所を返す。
     * 記録が無いか、そのワールドが無ければメインワールドのスポーンを返す。
     */
    fun returnLocation(player: UUID): Location {
        val saved = plugin.data.returnLocation(player) ?: return mainWorldSpawn()
        val key = runCatching { Key.key(saved.world) }.getOrNull() ?: return mainWorldSpawn()
        val world = plugin.server.getWorld(key)
        if (world == null || plugin.worlds.typeOf(key) != null) return mainWorldSpawn()
        return Location(world, saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
    }

    /** /main の本体。引数なし・確認なしで即実行する。 */
    fun teleportToMain(player: Player) {
        if (plugin.worlds.typeOf(player.world.key()) == null) {
            plugin.messages.send(player, "main.not-in-resource")
            return
        }
        tasks.teleport(player, returnLocation(player.uniqueId), beforeTeleport = { rememberDeparture(player) }).thenAccept { success ->
            plugin.messages.send(player, if (success) "main.teleporting" else "resource.teleport-failed")
        }
    }

    /**
     * 前回そのワールドを離れた場所。次にそのワールドへ行くときの移動先になる。
     * 記録が無いときと、ボーダーを縮めて範囲外になったときは null を返す（スポーンへ送る）。
     */
    fun lastLocationIn(player: UUID, world: World): Location? {
        val saved = plugin.data.resourceLocation(player, world.key()) ?: return null
        val location = Location(world, saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
        return location.takeIf { world.worldBorder.isInside(it) }
    }

    /**
     * ワールドを離れる直前に現在地を記録する。所有リージョンから呼ぶこと。
     * メインワールド側なら /main の戻り先に、資源ワールドなら次に来たときの移動先になる。
     */
    private fun rememberDeparture(player: Player) {
        val location = player.location
        val world = location.world ?: return
        val saved = SavedLocation(world.key().asString(), location.x, location.y, location.z, location.yaw, location.pitch)
        val type = plugin.worlds.typeOf(world.key())
        when {
            type == null -> plugin.data.setReturnLocation(player.uniqueId, saved)
            player.isFlying || isSafeToReturn(world, location) -> plugin.data.setResourceLocation(player.uniqueId, saved)
            else -> {
                // 溶岩の中や落下中に離れた人を、同じ場所へ戻して死なせない。
                plugin.data.clearResourceLocation(player.uniqueId, world.key())
                plugin.messages.send(player, "resource.location-not-saved", "world" to plugin.messages.worldName(type))
            }
        }
        plugin.data.saveAsync()
    }

    private fun isSafeToReturn(world: World, location: Location): Boolean {
        val xs = listOf(floor(location.x - HALF_PLAYER_WIDTH).toInt(), floor(location.x + HALF_PLAYER_WIDTH).toInt()).distinct()
        val zs = listOf(floor(location.z - HALF_PLAYER_WIDTH).toInt(), floor(location.z + HALF_PLAYER_WIDTH).toInt()).distinct()
        val columns = xs.flatMap { x ->
            zs.map { z -> { y: Int -> footing(world.getBlockAt(x, y, z)) } }
        }
        return SafeSpawn.isSafeToReturn(location.blockY, columns)
    }

    private fun footing(block: Block): FootingKind {
        val material = block.type
        return when {
            material in DANGEROUS -> FootingKind.DANGER
            material.isSolid || block.isLiquid || Tag.CLIMBABLE.isTagged(material) -> FootingKind.SUPPORT
            else -> FootingKind.OPEN
        }
    }

    fun spawnOf(world: World, type: ResourceType): Location =
        if (type == ResourceType.END) endSpawn(world) else centered(world.spawnLocation)

    /** 新しく作成またはリセットされたワールドのスポーンを安全な場所にする。 */
    fun prepare(world: World, type: ResourceType): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val future = if (type == ResourceType.END) {
            ensureEndPlatform(world).thenRun {
                setSpawn(world, END_X, END_Y, END_Z, END_YAW)
            }.thenApply { }
        } else {
            findSafeSpawn(world, type)
        }
        future.whenComplete { _, error ->
            if (error != null) {
                plugin.logger.warning("${world.key().asString()} のスポーン確保に失敗しました: ${rootMessage(error)}")
            }
            result.complete(Unit)
        }
        return result
    }

    /** /resource の本体。引数なし・確認なしで即実行する。 */
    fun teleportToResource(player: Player, type: ResourceType) {
        val name = plugin.messages.worldName(type)
        when (plugin.worlds.targetState(type)) {
            TargetState.RESETTING -> {
                plugin.messages.send(player, "resource.resetting", "world" to name)
                return
            }
            TargetState.UNAVAILABLE -> {
                plugin.messages.send(player, "resource.unavailable", "world" to name)
                return
            }
            TargetState.READY -> Unit
        }
        val world = plugin.worlds.world(type)
        if (world == null) {
            plugin.messages.send(player, "resource.unavailable", "world" to name)
            return
        }
        // 同じワールドにいるときはスポーンへ送る。前回の場所が悪かったときの逃げ道にもなる。
        val alreadyThere = player.world == world
        val last = if (alreadyThere) null else lastLocationIn(player.uniqueId, world)
        val ready = if (last == null && type == ResourceType.END) ensureEndPlatform(world) else CompletableFuture.completedFuture(Unit)
        ready.whenComplete { _, _ ->
            // 足場を作っている間にリセットが始まっていないか確認する。
            if (plugin.worlds.targetState(type) != TargetState.READY) {
                plugin.messages.send(player, "resource.resetting", "world" to name)
                return@whenComplete
            }
            val target = last ?: spawnOf(world, type)
            tasks.teleport(player, target, beforeTeleport = { if (!alreadyThere) rememberDeparture(player) }).thenAccept { success ->
                val path = when {
                    !success -> "resource.teleport-failed"
                    last != null -> "resource.teleporting-last"
                    else -> "resource.teleporting"
                }
                plugin.messages.send(player, path, "world" to name)
            }
        }
    }

    /**
     * エンドの黒曜石の足場をバニラと同じ位置に作る。チャンクをまたぐので、チャンクごとに所有リージョンで置く。
     */
    private fun ensureEndPlatform(world: World): CompletableFuture<Unit> {
        val chunks = listOf(6 to 0, 6 to -1)
        val futures = chunks.map { (chunkX, chunkZ) ->
            val done = CompletableFuture<Unit>()
            world.getChunkAtAsync(chunkX, chunkZ).whenComplete { _, error ->
                if (error != null) {
                    done.completeExceptionally(error)
                    return@whenComplete
                }
                tasks.region(world, chunkX, chunkZ) {
                    try {
                        placePlatformPart(world, chunkZ)
                        done.complete(Unit)
                    } catch (e: Exception) {
                        done.completeExceptionally(e)
                    }
                }
            }
            done
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { }
    }

    private fun placePlatformPart(world: World, chunkZ: Int) {
        for (x in END_X - 2..END_X + 2) {
            for (z in END_Z - 2..END_Z + 2) {
                if (z shr 4 != chunkZ) continue
                placeColumn(world, x, END_Y, z)
            }
        }
    }

    /** 足元 1 段を黒曜石、その上 3 段を空気にする。 */
    private fun placeColumn(world: World, x: Int, feetY: Int, z: Int) {
        world.getBlockAt(x, feetY - 1, z).setType(Material.OBSIDIAN, false)
        for (y in feetY..feetY + 2) {
            world.getBlockAt(x, y, z).setType(Material.AIR, false)
        }
    }

    private fun findSafeSpawn(world: World, type: ResourceType): CompletableFuture<Unit> {
        val spawn = world.spawnLocation
        val centerChunkX = spawn.blockX shr 4
        val centerChunkZ = spawn.blockZ shr 4
        val offsets = SafeSpawn.chunkSpiral(SEARCH_CHUNK_RADIUS)
        val result = CompletableFuture<Unit>()

        fun scan(index: Int) {
            if (index >= offsets.size) {
                buildFallbackPlatform(world, type, centerChunkX, centerChunkZ, result)
                return
            }
            val chunkX = centerChunkX + offsets[index].first
            val chunkZ = centerChunkZ + offsets[index].second
            world.getChunkAtAsync(chunkX, chunkZ).whenComplete { _, error ->
                if (error != null) {
                    result.completeExceptionally(error)
                    return@whenComplete
                }
                tasks.region(world, chunkX, chunkZ) {
                    try {
                        if (index == 0 && isStandable(world, spawn.blockX, spawn.blockY, spawn.blockZ)) {
                            result.complete(Unit)
                            return@region
                        }
                        val found = scanChunk(world, type, chunkX, chunkZ)
                        if (found != null) {
                            setSpawn(world, found.first, found.second, found.third, 0f)
                            result.complete(Unit)
                        } else {
                            scan(index + 1)
                        }
                    } catch (e: Exception) {
                        result.completeExceptionally(e)
                    }
                }
            }
        }
        scan(0)
        return result
    }

    private fun scanChunk(world: World, type: ResourceType, chunkX: Int, chunkZ: Int): Triple<Int, Int, Int>? {
        for ((dx, dz) in SafeSpawn.columnsCenterFirst()) {
            val x = chunkX * 16 + dx
            val z = chunkZ * 16 + dz
            val y = if (type == ResourceType.NETHER) {
                // ネザーは天井の岩盤の下から、洞窟内の立てる場所を探す。
                SafeSpawn.findStandingY(min(NETHER_TOP, world.maxHeight - 3), world.minHeight + 6) { kind(world.getBlockAt(x, it, z)) }
            } else {
                // オーバーワールドは地表だけを見る（海底の洞窟などへスポーンさせないため）。
                val surface = world.getHighestBlockYAt(x, z) + 1
                SafeSpawn.findStandingY(surface, surface) { kind(world.getBlockAt(x, it, z)) }
            }
            if (y != null) return Triple(x, y, z)
        }
        return null
    }

    private fun isStandable(world: World, x: Int, y: Int, z: Int): Boolean =
        SafeSpawn.isStandable(y) { kind(world.getBlockAt(x, it, z)) }

    /** 周囲に立てる場所が無いときは、スポーンチャンクの中央に足場を作る。 */
    private fun buildFallbackPlatform(
        world: World,
        type: ResourceType,
        chunkX: Int,
        chunkZ: Int,
        result: CompletableFuture<Unit>,
    ) {
        tasks.region(world, chunkX, chunkZ) {
            try {
                val centerX = chunkX * 16 + 8
                val centerZ = chunkZ * 16 + 8
                val feetY = if (type == ResourceType.NETHER) 64 else world.getHighestBlockYAt(centerX, centerZ) + 2
                for (x in centerX - 2..centerX + 2) {
                    for (z in centerZ - 2..centerZ + 2) {
                        placeColumn(world, x, feetY, z)
                    }
                }
                setSpawn(world, centerX, feetY, centerZ, 0f)
                plugin.logger.warning("${world.key().asString()} の周囲に安全な場所が無いため、足場を作成しました ($centerX, $feetY, $centerZ)")
                result.complete(Unit)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
    }

    private fun setSpawn(world: World, x: Int, y: Int, z: Int, yaw: Float) {
        tasks.global { world.setSpawnLocation(x, y, z, yaw) }
    }

    private fun kind(block: Block): BlockKind {
        val material = block.type
        return when {
            material in DANGEROUS -> BlockKind.UNSAFE
            block.isLiquid -> BlockKind.UNSAFE
            material.isSolid -> BlockKind.FLOOR
            block.isPassable -> BlockKind.PASSABLE
            else -> BlockKind.UNSAFE
        }
    }

    private fun endSpawn(world: World) = Location(world, END_X + 0.5, END_Y.toDouble(), END_Z + 0.5, END_YAW, 0f)

    private fun centered(location: Location) = Location(
        location.world,
        location.blockX + 0.5,
        location.blockY.toDouble(),
        location.blockZ + 0.5,
        location.yaw,
        location.pitch,
    )

    companion object {
        val MAIN_WORLD: Key = Key.key("overworld")

        private const val SEARCH_CHUNK_RADIUS = 4
        private const val NETHER_TOP = 120
        private const val HALF_PLAYER_WIDTH = 0.3

        // バニラの ServerLevel.END_SPAWN_POINT と EndPlatformFeature に合わせた座標。
        private const val END_X = 100
        private const val END_Y = 49
        private const val END_Z = 0
        private const val END_YAW = 90f

        private val DANGEROUS = setOf(
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE,
            Material.COBWEB,
            Material.POWDER_SNOW,
        )
    }
}
