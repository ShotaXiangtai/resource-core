package jp.vcmc.resourcecore.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginPolicyTest {
    private val base = LoginContext(
        newPlayer = false,
        joinMode = HubJoinMode.FIRST_JOIN,
        hubAvailable = true,
        inResourceWorld = false,
        worldResetting = false,
        worldReady = true,
        lastSeenMillis = 1_000,
        lastResetMillis = null,
        relocateTarget = RelocateTarget.HUB,
    )

    @Test
    fun `logged out before the reset is stale`() {
        assertTrue(LoginPolicy.isStale(lastSeenMillis = 1_000, lastResetMillis = 2_000))
    }

    @Test
    fun `logged out after the reset is not stale`() {
        assertFalse(LoginPolicy.isStale(lastSeenMillis = 3_000, lastResetMillis = 2_000))
    }

    @Test
    fun `never reset is not stale`() {
        assertFalse(LoginPolicy.isStale(lastSeenMillis = 1_000, lastResetMillis = null))
    }

    @Test
    fun `new player goes to the hub in first join mode`() {
        assertEquals(LoginDecision.TO_HUB, LoginPolicy.decide(base.copy(newPlayer = true)))
    }

    @Test
    fun `returning player stays in first join mode`() {
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(base))
    }

    @Test
    fun `always mode sends everyone to the hub`() {
        assertEquals(LoginDecision.TO_HUB, LoginPolicy.decide(base.copy(joinMode = HubJoinMode.ALWAYS)))
    }

    @Test
    fun `new player stays when the hub is unset`() {
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(base.copy(newPlayer = true, hubAvailable = false)))
    }

    @Test
    fun `stale resource player goes to the hub`() {
        val context = base.copy(inResourceWorld = true, lastResetMillis = 2_000)
        assertEquals(LoginDecision.TO_HUB, LoginPolicy.decide(context))
    }

    @Test
    fun `stale resource player falls back to the survival spawn without a hub`() {
        val context = base.copy(inResourceWorld = true, lastResetMillis = 2_000, hubAvailable = false)
        assertEquals(LoginDecision.TO_SURVIVAL_SPAWN, LoginPolicy.decide(context))
    }

    @Test
    fun `stale resource player can go to the resource spawn`() {
        val context = base.copy(inResourceWorld = true, lastResetMillis = 2_000, relocateTarget = RelocateTarget.RESOURCE_SPAWN)
        assertEquals(LoginDecision.TO_RESOURCE_SPAWN, LoginPolicy.decide(context))
    }

    @Test
    fun `resource spawn target falls back when the world is not ready`() {
        val context = base.copy(
            inResourceWorld = true,
            lastResetMillis = 2_000,
            relocateTarget = RelocateTarget.RESOURCE_SPAWN,
            worldReady = false,
        )
        assertEquals(LoginDecision.TO_SURVIVAL_SPAWN, LoginPolicy.decide(context))
    }

    @Test
    fun `login during a reset goes to the survival spawn`() {
        val context = base.copy(inResourceWorld = true, worldResetting = true, worldReady = false)
        assertEquals(LoginDecision.TO_SURVIVAL_SPAWN, LoginPolicy.decide(context))
    }

    @Test
    fun `player who logged out after the reset stays in the resource world`() {
        val context = base.copy(inResourceWorld = true, lastSeenMillis = 3_000, lastResetMillis = 2_000)
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(context))
    }

    @Test
    fun `reset of a world the player is not in does not move them`() {
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(base.copy(inResourceWorld = false, lastResetMillis = 2_000)))
    }
}
