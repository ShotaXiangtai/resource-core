package jp.vcmc.resourcecore.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalRoutingTest {
    @Test
    fun `overworld portals lead to nether and end`() {
        assertEquals(ResourceType.NETHER, PortalRouting.target(ResourceType.OVERWORLD, PortalKind.NETHER))
        assertEquals(ResourceType.END, PortalRouting.target(ResourceType.OVERWORLD, PortalKind.END))
    }

    @Test
    fun `nether and end return to the overworld`() {
        assertEquals(ResourceType.OVERWORLD, PortalRouting.target(ResourceType.NETHER, PortalKind.NETHER))
        assertEquals(ResourceType.OVERWORLD, PortalRouting.target(ResourceType.END, PortalKind.END))
    }

    private fun decide(
        source: ResourceType = ResourceType.OVERWORLD,
        kind: PortalKind = PortalKind.NETHER,
        isPlayer: Boolean = true,
        targetState: TargetState = TargetState.READY,
        routeBroken: Boolean = false,
    ) = PortalRouting.decide(source, kind, isPlayer, targetState, routeBroken)

    @Test
    fun `normal portals are left to Worlds`() {
        assertEquals(PortalAction.PASS, decide())
        assertEquals(PortalAction.PASS, decide(source = ResourceType.NETHER))
    }

    @Test
    fun `blocked while the target is resetting`() {
        assertEquals(PortalAction.BLOCK_RESETTING, decide(targetState = TargetState.RESETTING))
    }

    @Test
    fun `blocked when the target is unavailable or the route is broken`() {
        assertEquals(PortalAction.BLOCK_UNAVAILABLE, decide(targetState = TargetState.UNAVAILABLE))
        assertEquals(PortalAction.BLOCK_UNAVAILABLE, decide(routeBroken = true))
    }

    @Test
    fun `players leaving the end are returned to the overworld by the plugin`() {
        assertEquals(PortalAction.RETURN_TO_OVERWORLD, decide(source = ResourceType.END, kind = PortalKind.END))
    }

    @Test
    fun `non players leaving the end are left to Worlds`() {
        assertEquals(PortalAction.PASS, decide(source = ResourceType.END, kind = PortalKind.END, isPlayer = false))
    }

    @Test
    fun `leaving the end is blocked while the overworld is resetting`() {
        assertEquals(
            PortalAction.BLOCK_RESETTING,
            decide(source = ResourceType.END, kind = PortalKind.END, targetState = TargetState.RESETTING),
        )
    }
}
