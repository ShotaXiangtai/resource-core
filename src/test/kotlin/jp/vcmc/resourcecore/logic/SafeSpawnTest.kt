package jp.vcmc.resourcecore.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeSpawnTest {
    private fun column(vararg entries: Pair<Int, BlockKind>): (Int) -> BlockKind {
        val map = entries.toMap()
        return { y -> map[y] ?: BlockKind.PASSABLE }
    }

    @Test
    fun `finds the first standable position from the top`() {
        val kinds = column(70 to BlockKind.FLOOR, 60 to BlockKind.FLOOR)
        assertEquals(71, SafeSpawn.findStandingY(100, 0, kinds))
    }

    @Test
    fun `lava floor is not standable`() {
        val kinds = column(70 to BlockKind.UNSAFE, 60 to BlockKind.FLOOR)
        assertEquals(61, SafeSpawn.findStandingY(100, 0, kinds))
    }

    @Test
    fun `needs two free blocks above the floor`() {
        val kinds = column(70 to BlockKind.FLOOR, 72 to BlockKind.FLOOR)
        assertFalse(SafeSpawn.isStandable(71, kinds))
        assertEquals(73, SafeSpawn.findStandingY(100, 0, kinds))
    }

    @Test
    fun `returns null when there is nowhere to stand`() {
        assertNull(SafeSpawn.findStandingY(100, 0) { BlockKind.PASSABLE })
    }

    @Test
    fun `stops at the bottom limit`() {
        val kinds = column(5 to BlockKind.FLOOR)
        assertNull(SafeSpawn.findStandingY(100, 10, kinds))
    }

    private fun footing(vararg entries: Pair<Int, FootingKind>): (Int) -> FootingKind {
        val map = entries.toMap()
        return { y -> map[y] ?: FootingKind.OPEN }
    }

    @Test
    fun `standing on the ground is safe to return to`() {
        assertTrue(SafeSpawn.isSafeToReturn(64, listOf(footing(63 to FootingKind.SUPPORT))))
    }

    @Test
    fun `standing inside a partial block is safe to return to`() {
        assertTrue(SafeSpawn.isSafeToReturn(64, listOf(footing(64 to FootingKind.SUPPORT))))
    }

    @Test
    fun `a jump above the ground is safe to return to`() {
        assertTrue(SafeSpawn.isSafeToReturn(66, listOf(footing(64 to FootingKind.SUPPORT))))
    }

    @Test
    fun `falling with nothing below is not safe to return to`() {
        assertFalse(SafeSpawn.isSafeToReturn(100, listOf(footing())))
        assertFalse(SafeSpawn.isSafeToReturn(100, listOf(footing(90 to FootingKind.SUPPORT))))
    }

    @Test
    fun `being in lava is not safe to return to`() {
        val feetInLava = footing(64 to FootingKind.DANGER, 63 to FootingKind.SUPPORT)
        val headInLava = footing(65 to FootingKind.DANGER, 63 to FootingKind.SUPPORT)
        assertFalse(SafeSpawn.isSafeToReturn(64, listOf(feetInLava)))
        assertFalse(SafeSpawn.isSafeToReturn(64, listOf(headInLava)))
    }

    @Test
    fun `falling toward lava is not safe to return to`() {
        assertFalse(SafeSpawn.isSafeToReturn(64, listOf(footing(62 to FootingKind.DANGER))))
    }

    @Test
    fun `standing on the edge of a cliff is safe to return to`() {
        assertTrue(SafeSpawn.isSafeToReturn(64, listOf(footing(), footing(63 to FootingKind.SUPPORT))))
    }

    @Test
    fun `touching lava with part of the body is not safe to return to`() {
        val ground = footing(63 to FootingKind.SUPPORT)
        val lava = footing(64 to FootingKind.DANGER, 63 to FootingKind.SUPPORT)
        assertFalse(SafeSpawn.isSafeToReturn(64, listOf(ground, lava)))
    }

    @Test
    fun `chunk spiral starts at the center and covers the square once`() {
        val spiral = SafeSpawn.chunkSpiral(4)
        assertEquals(0 to 0, spiral.first())
        assertEquals(81, spiral.size)
        assertEquals(81, spiral.toSet().size)
        assertTrue(spiral.all { (x, z) -> x in -4..4 && z in -4..4 })
    }

    @Test
    fun `columns are center first and cover the whole chunk`() {
        val columns = SafeSpawn.columnsCenterFirst()
        assertEquals(8 to 8, columns.first())
        assertEquals(256, columns.toSet().size)
    }
}
