package jp.vcmc.resourcecore.logic

enum class RelocateTarget { MAIN_WORLD, RESOURCE_SPAWN }
enum class LoginDecision { STAY, TO_RESOURCE_SPAWN, TO_MAIN_WORLD }

/** ログイン時の移動先を決めるための入力。 */
data class LoginContext(
    /** 保存されていた位置が資源ワールド内か。 */
    val inResourceWorld: Boolean,
    /** そのワールドがリセット処理中か。 */
    val worldResetting: Boolean,
    /** そのワールドが利用可能か（ロード済みかつリセット中でない）。 */
    val worldReady: Boolean,
    val lastSeenMillis: Long,
    val lastResetMillis: Long?,
    val relocateTarget: RelocateTarget,
)

object LoginPolicy {
    /** 最後にサーバーにいた時刻より後にリセットされていれば、保存された位置はもう無効。 */
    fun isStale(lastSeenMillis: Long, lastResetMillis: Long?): Boolean =
        lastResetMillis != null && lastSeenMillis < lastResetMillis

    fun decide(ctx: LoginContext): LoginDecision {
        if (!ctx.inResourceWorld) return LoginDecision.STAY
        if (ctx.worldResetting) return LoginDecision.TO_MAIN_WORLD
        if (!isStale(ctx.lastSeenMillis, ctx.lastResetMillis)) return LoginDecision.STAY
        return when (ctx.relocateTarget) {
            RelocateTarget.MAIN_WORLD -> LoginDecision.TO_MAIN_WORLD
            RelocateTarget.RESOURCE_SPAWN ->
                if (ctx.worldReady) LoginDecision.TO_RESOURCE_SPAWN else LoginDecision.TO_MAIN_WORLD
        }
    }
}

/** ワールドボーダーの半径。ネザーは座標が 8 分の 1 になるので、オーバーワールドと同じ範囲に揃える。 */
fun borderRadius(type: ResourceType, radius: Int): Int =
    if (type == ResourceType.NETHER) maxOf(1, radius / NETHER_SCALE) else radius

private const val NETHER_SCALE = 8
