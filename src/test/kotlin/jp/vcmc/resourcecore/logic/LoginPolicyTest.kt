package jp.vcmc.resourcecore.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginPolicyTest {
    private val base = LoginContext(
        inResourceWorld = true,
        worldResetting = false,
        worldReady = true,
        lastSeenMillis = 1_000,
        lastResetMillis = null,
        relocateTarget = RelocateTarget.MAIN_WORLD,
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
    fun `players outside resource worlds are not moved`() {
        val context = base.copy(inResourceWorld = false, lastResetMillis = 2_000)
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(context))
    }

    @Test
    fun `stale resource player goes back to the main world`() {
        assertEquals(LoginDecision.TO_MAIN_WORLD, LoginPolicy.decide(base.copy(lastResetMillis = 2_000)))
    }

    @Test
    fun `stale resource player can go to the resource spawn`() {
        val context = base.copy(lastResetMillis = 2_000, relocateTarget = RelocateTarget.RESOURCE_SPAWN)
        assertEquals(LoginDecision.TO_RESOURCE_SPAWN, LoginPolicy.decide(context))
    }

    @Test
    fun `resource spawn target falls back when the world is not ready`() {
        val context = base.copy(lastResetMillis = 2_000, relocateTarget = RelocateTarget.RESOURCE_SPAWN, worldReady = false)
        assertEquals(LoginDecision.TO_MAIN_WORLD, LoginPolicy.decide(context))
    }

    @Test
    fun `login during a reset goes back to the main world`() {
        val context = base.copy(worldResetting = true, worldReady = false)
        assertEquals(LoginDecision.TO_MAIN_WORLD, LoginPolicy.decide(context))
    }

    @Test
    fun `player who logged out after the reset stays in the resource world`() {
        val context = base.copy(lastSeenMillis = 3_000, lastResetMillis = 2_000)
        assertEquals(LoginDecision.STAY, LoginPolicy.decide(context))
    }

    @Test
    fun `nether border is one eighth of the radius`() {
        assertEquals(10_000, borderRadius(ResourceType.OVERWORLD, 10_000))
        assertEquals(10_000, borderRadius(ResourceType.END, 10_000))
        assertEquals(1_250, borderRadius(ResourceType.NETHER, 10_000))
        assertEquals(1, borderRadius(ResourceType.NETHER, 4))
    }
}
