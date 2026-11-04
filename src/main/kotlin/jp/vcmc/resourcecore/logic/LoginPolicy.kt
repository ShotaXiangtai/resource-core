package jp.vcmc.resourcecore.logic

enum class HubJoinMode { FIRST_JOIN, ALWAYS }
enum class RelocateTarget { HUB, RESOURCE_SPAWN }
enum class LoginDecision { STAY, TO_HUB, TO_RESOURCE_SPAWN, TO_SURVIVAL_SPAWN }

/** ログイン時の移動先を決めるための入力。 */
data class LoginContext(
    val newPlayer: Boolean,
    val joinMode: HubJoinMode,
    val hubAvailable: Boolean,
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
        val hubJoin = ctx.joinMode == HubJoinMode.ALWAYS || ctx.newPlayer
        if (hubJoin && ctx.hubAvailable) return LoginDecision.TO_HUB
        if (!ctx.inResourceWorld) return LoginDecision.STAY
        if (ctx.worldResetting) return LoginDecision.TO_SURVIVAL_SPAWN
        if (!isStale(ctx.lastSeenMillis, ctx.lastResetMillis)) return LoginDecision.STAY
        return when (ctx.relocateTarget) {
            RelocateTarget.HUB ->
                if (ctx.hubAvailable) LoginDecision.TO_HUB else LoginDecision.TO_SURVIVAL_SPAWN
            RelocateTarget.RESOURCE_SPAWN ->
                if (ctx.worldReady) LoginDecision.TO_RESOURCE_SPAWN else LoginDecision.TO_SURVIVAL_SPAWN
        }
    }
}
