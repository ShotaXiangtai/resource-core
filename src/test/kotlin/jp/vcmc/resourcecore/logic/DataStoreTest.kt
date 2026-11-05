package jp.vcmc.resourcecore.logic

import jp.vcmc.resourcecore.config.DataStore
import jp.vcmc.resourcecore.config.SavedLocation
import net.kyori.adventure.key.Key
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataStoreTest {
    private fun store(file: File) = DataStore(file, Logger.getAnonymousLogger()) { it() }

    private fun tempFile(): File = File(Files.createTempDirectory("resource-core-test").toFile(), "data.yml")

    @Test
    fun `values survive a save and load`() {
        val file = tempFile()
        val key = Key.key("resource:the.end")
        val player = UUID.randomUUID()
        val location = SavedLocation("minecraft:overworld", 10.5, 64.0, -3.25, 90f, -15f)

        val first = store(file)
        first.setLastReset(key, 1234L)
        first.setSpawnPrepared(key, true)
        first.setPregenPending(key, true)
        first.setReturnLocation(player, location)
        first.saveNow()

        val second = store(file)
        second.load()
        assertEquals(1234L, second.lastReset(key))
        assertTrue(second.isSpawnPrepared(key))
        assertTrue(second.isPregenPending(key))
        assertEquals(location, second.returnLocation(player))
    }

    @Test
    fun `resource locations survive a save and load per world`() {
        val file = tempFile()
        val player = UUID.randomUUID()
        val overworld = SavedLocation("resource:overworld", 1.5, 70.0, 2.5, 10f, 20f)
        val end = SavedLocation("resource:the.end", 100.5, 49.0, 0.5, 90f, 0f)

        val first = store(file)
        first.setResourceLocation(player, overworld)
        first.setResourceLocation(player, end)
        first.saveNow()

        val second = store(file)
        second.load()
        assertEquals(overworld, second.resourceLocation(player, Key.key("resource:overworld")))
        assertEquals(end, second.resourceLocation(player, Key.key("resource:the.end")))
        assertNull(second.resourceLocation(player, Key.key("resource:nether")))
    }

    @Test
    fun `the latest resource location replaces the previous one`() {
        val store = store(tempFile())
        val player = UUID.randomUUID()
        val key = Key.key("resource:overworld")
        store.setResourceLocation(player, SavedLocation(key.asString(), 1.0, 2.0, 3.0, 0f, 0f))
        val latest = SavedLocation(key.asString(), 4.0, 5.0, 6.0, 0f, 0f)
        store.setResourceLocation(player, latest)
        assertEquals(latest, store.resourceLocation(player, key))
    }

    @Test
    fun `a reset forgets that world for every player and keeps other worlds`() {
        val file = tempFile()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val overworld = Key.key("resource:overworld")
        val nether = Key.key("resource:nether")
        val first = store(file)
        first.setResourceLocation(alice, SavedLocation(overworld.asString(), 1.0, 2.0, 3.0, 0f, 0f))
        first.setResourceLocation(bob, SavedLocation(overworld.asString(), 4.0, 5.0, 6.0, 0f, 0f))
        val kept = SavedLocation(nether.asString(), 7.0, 8.0, 9.0, 0f, 0f)
        first.setResourceLocation(alice, kept)

        first.clearResourceLocations(overworld)
        first.saveNow()

        val second = store(file)
        second.load()
        assertNull(second.resourceLocation(alice, overworld))
        assertNull(second.resourceLocation(bob, overworld))
        assertEquals(kept, second.resourceLocation(alice, nether))
    }

    @Test
    fun `one player's location can be forgotten`() {
        val store = store(tempFile())
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val key = Key.key("resource:overworld")
        val location = SavedLocation(key.asString(), 1.0, 2.0, 3.0, 0f, 0f)
        store.setResourceLocation(alice, location)
        store.setResourceLocation(bob, location)
        store.clearResourceLocation(alice, key)
        assertNull(store.resourceLocation(alice, key))
        assertEquals(location, store.resourceLocation(bob, key))
    }

    @Test
    fun `missing file means no history`() {
        val store = store(tempFile())
        store.load()
        assertNull(store.lastReset(Key.key("resource:overworld")))
        assertFalse(store.isPregenPending(Key.key("resource:overworld")))
        assertNull(store.returnLocation(UUID.randomUUID()))
    }

    @Test
    fun `flags can be cleared`() {
        val file = tempFile()
        val key = Key.key("resource:nether")
        val first = store(file)
        first.setPregenPending(key, true)
        first.setPregenPending(key, false)
        first.saveNow()
        val second = store(file)
        second.load()
        assertFalse(second.isPregenPending(key))
    }

    @Test
    fun `legacy hub rules from 1_0_0 are read`() {
        val file = tempFile()
        file.writeText(
            """
            hub-rules:
              world: lobby:hub
              originals:
                minecraft:pvp: true
            """.trimIndent(),
        )
        val store = store(file)
        store.load()
        assertEquals("lobby:hub", store.legacyHubRules?.world)
        assertEquals(true, store.legacyHubRules?.originals?.get("minecraft:pvp"))
    }
}
