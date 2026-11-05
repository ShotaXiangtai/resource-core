package jp.vcmc.resourcecore.logic

/** スポーン判定で見るブロックの分類。 */
enum class BlockKind { FLOOR, PASSABLE, UNSAFE }

/** 離れた場所へ戻しても安全かの判定で見るブロックの分類。 */
enum class FootingKind {
    /** 空気など。何もしてくれない。 */
    OPEN,

    /** 足場になる。固体のほか、落下を止める水やはしごも含む。 */
    SUPPORT,

    /** 溶岩や炎など、触れると危険。 */
    DANGER,
}

object SafeSpawn {
    /** これ以上の落下はダメージになるので、足場を探す深さの上限にする。 */
    private const val MAX_SAFE_DROP = 4

    /**
     * その場所へ戻しても安全かを判定する。溶岩の中や奈落へ落下中に資源ワールドを離れた人を、
     * 同じ場所へ戻して死なせないために使う。
     *
     * プレイヤーの当たり判定は複数の列にまたがるので、重なる列をすべて渡す。崖の縁に立っているときは
     * 中心の真下が空でも、どこかの列に足場があれば安全とする。
     */
    fun isSafeToReturn(feetY: Int, columns: List<(Int) -> FootingKind>): Boolean {
        if (columns.any { it(feetY) == FootingKind.DANGER || it(feetY + 1) == FootingKind.DANGER }) return false
        return columns.any { hasSupportBelow(feetY, it) }
    }

    private fun hasSupportBelow(feetY: Int, kindAt: (Int) -> FootingKind): Boolean {
        for (y in feetY downTo feetY - MAX_SAFE_DROP) {
            when (kindAt(y)) {
                FootingKind.OPEN -> continue
                FootingKind.SUPPORT -> return true
                FootingKind.DANGER -> return false
            }
        }
        return false
    }

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
