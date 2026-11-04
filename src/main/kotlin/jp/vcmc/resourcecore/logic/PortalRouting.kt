package jp.vcmc.resourcecore.logic

enum class PortalKind { NETHER, END }
enum class TargetState { READY, RESETTING, UNAVAILABLE }
enum class PortalAction {
    /** Worlds の処理に任せる。 */
    PASS,

    /** リセット中のため止めて「リセット中です」と表示する。 */
    BLOCK_RESETTING,

    /** 連携先が使えないため止める。 */
    BLOCK_UNAVAILABLE,

    /** 自前で資源オーバーワールドへ帰還させる（Worlds に任せるとベッドへ飛ぶため）。 */
    RETURN_TO_OVERWORLD,
}

object PortalRouting {
    /** ポータルの行き先の資源ワールド。Worlds の連携規則と同じ対応。 */
    fun target(source: ResourceType, kind: PortalKind): ResourceType = when (kind) {
        PortalKind.NETHER -> if (source == ResourceType.NETHER) ResourceType.OVERWORLD else ResourceType.NETHER
        PortalKind.END -> if (source == ResourceType.END) ResourceType.OVERWORLD else ResourceType.END
    }

    fun decide(
        source: ResourceType,
        kind: PortalKind,
        isPlayer: Boolean,
        targetState: TargetState,
        routeBroken: Boolean,
    ): PortalAction {
        if (targetState == TargetState.RESETTING) return PortalAction.BLOCK_RESETTING
        if (routeBroken || targetState == TargetState.UNAVAILABLE) return PortalAction.BLOCK_UNAVAILABLE
        if (source == ResourceType.END && kind == PortalKind.END && isPlayer) return PortalAction.RETURN_TO_OVERWORLD
        return PortalAction.PASS
    }
}
