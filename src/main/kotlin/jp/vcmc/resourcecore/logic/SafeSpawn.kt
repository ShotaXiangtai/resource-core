package jp.vcmc.resourcecore.logic

/** スポーン判定で見るブロックの分類。 */
enum class BlockKind { FLOOR, PASSABLE, UNSAFE }

object SafeSpawn {
    /** 足元 y を上から探す。足と頭が通行可能で、その下が安全な床である最初の y を返す。 */
    fun findStandingY(top: Int, bottom: Int, kindAt: (Int) -> BlockKind): Int? {
        var y = top
        while (y >= bottom) {
            if (isStandable(y, kindAt)) return y
            y--
        }
        return null
    }

    fun isStandable(y: Int, kindAt: (Int) -> BlockKind): Boolean =
        kindAt(y - 1) == BlockKind.FLOOR && kindAt(y) == BlockKind.PASSABLE && kindAt(y + 1) == BlockKind.PASSABLE

    /** チャンク内の列を中央に近い順に返す（dx, dz は 0..15）。 */
    fun columnsCenterFirst(): List<Pair<Int, Int>> =
        (0..15).flatMap { x -> (0..15).map { z -> x to z } }
            .sortedBy { (x, z) -> (x - 8) * (x - 8) + (z - 8) * (z - 8) }

    /** 中心から外へ向かう正方形の渦巻きで、チャンク座標のオフセットを返す。 */
    fun chunkSpiral(radius: Int): List<Pair<Int, Int>> {
        val result = mutableListOf(0 to 0)
        for (r in 1..radius) {
            for (x in -r..r) {
                result += x to -r
                result += x to r
            }
            for (z in (-r + 1) until r) {
                result += -r to z
                result += r to z
            }
        }
        return result
    }
}
