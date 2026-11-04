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
