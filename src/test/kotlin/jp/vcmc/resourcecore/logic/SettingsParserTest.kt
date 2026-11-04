package jp.vcmc.resourcecore.logic

import jp.vcmc.resourcecore.config.SettingsParser
import net.kyori.adventure.key.Key
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsParserTest {
    private fun parse(yaml: String) = SettingsParser.parse(YamlConfiguration().apply { loadFromString(yaml) })

    @Test
    fun `an empty config gives working defaults without issues`() {
        val result = parse("")
        assertTrue(result.issues.isEmpty(), result.issues.toString())
        val settings = result.settings
        assertEquals(Key.key("resource:overworld"), settings.world(ResourceType.OVERWORLD).key)
        assertEquals(Key.key("resource:nether"), settings.world(ResourceType.NETHER).key)
        assertEquals(Key.key("resource:the_end"), settings.world(ResourceType.END).key)
        assertTrue(settings.world(ResourceType.END).changeSeed)
        assertEquals(listOf(300, 60, 10), settings.reset.countdown.map { it.seconds })
        assertFalse(settings.reset.backup)
        assertNull(settings.hub.key)
        assertEquals(HubJoinMode.FIRST_JOIN, settings.hub.joinMode)
        assertEquals(0, settings.hub.teleportDelaySeconds)
        assertEquals(RelocateTarget.HUB, settings.relocateTarget)
        assertEquals("Asia/Tokyo", settings.zone.id)
    }

    @Test
    fun `the bundled config has no issues`() {
        val file = listOf("src/main/resources/config.yml").map(::File).first { it.exists() }
        val result = SettingsParser.parse(YamlConfiguration.loadConfiguration(file))
        assertTrue(result.issues.isEmpty(), result.issues.toString())
    }

    @Test
    fun `invalid world key falls back with an issue`() {
        val result = parse("worlds:\n  nether:\n    key: 'Not A Key!'\n")
        assertEquals(Key.key("resource:nether"), result.settings.world(ResourceType.NETHER).key)
        assertEquals("worlds.nether.key", result.issues.single().path)
    }

    @Test
    fun `minecraft namespace is rejected so survival is untouched`() {
        val result = parse("worlds:\n  overworld:\n    key: 'minecraft:overworld'\n")
        assertEquals(Key.key("resource:overworld"), result.settings.world(ResourceType.OVERWORLD).key)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `mixed namespaces are reported`() {
        val result = parse("worlds:\n  nether:\n    key: 'other:nether'\n")
        assertEquals("worlds", result.issues.single().path)
    }

    @Test
    fun `duplicate world keys fall back`() {
        val result = parse("worlds:\n  nether:\n    key: 'resource:overworld'\n")
        assertEquals(Key.key("resource:nether"), result.settings.world(ResourceType.NETHER).key)
        assertEquals("worlds.nether.key", result.issues.single().path)
    }

    @Test
    fun `seeds are numbers or hashed strings`() {
        assertNull(SettingsParser.parseSeed(""))
        assertNull(SettingsParser.parseSeed(null))
        assertEquals(12345L, SettingsParser.parseSeed("12345"))
        assertEquals("hello".hashCode().toLong(), SettingsParser.parseSeed("hello"))
    }

    @Test
    fun `countdown is sorted and bad entries are dropped`() {
        val result = parse(
            """
            reset:
              countdown:
                - seconds: 10
                - seconds: 60
                  message: "<red>hi"
                - seconds: 0
                - seconds: 10
            """.trimIndent(),
        )
        assertEquals(listOf(60, 10), result.settings.reset.countdown.map { it.seconds })
        assertEquals("<red>hi", result.settings.reset.countdown.first().message)
        assertEquals(2, result.issues.size)
    }

    @Test
    fun `empty countdown means no countdown`() {
        val result = parse("reset:\n  countdown: []\n")
        assertTrue(result.settings.reset.countdown.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `hub cannot be a default or resource world`() {
        assertNull(parse("hub:\n  world: 'minecraft:overworld'\n").settings.hub.key)
        assertNull(parse("hub:\n  world: 'resource:nether'\n").settings.hub.key)
        assertEquals(Key.key("lobby:hub"), parse("hub:\n  world: 'lobby:hub'\n").settings.hub.key)
    }

    @Test
    fun `invalid choices fall back with an issue`() {
        val result = parse("hub:\n  teleport-on-join: sometimes\nlogin:\n  relocate-target: moon\n")
        assertEquals(HubJoinMode.FIRST_JOIN, result.settings.hub.joinMode)
        assertEquals(RelocateTarget.HUB, result.settings.relocateTarget)
        assertEquals(2, result.issues.size)
    }

    @Test
    fun `invalid timezone and date format fall back`() {
        val result = parse("display:\n  timezone: Mars/Base\n  date-format: 'qqqq{{'\n")
        assertEquals("Asia/Tokyo", result.settings.zone.id)
        assertEquals(2, result.issues.size)
    }

    @Test
    fun `negative numbers fall back`() {
        val result = parse("hub:\n  teleport-delay-seconds: -1\nreset:\n  move-timeout-seconds: 0\n")
        assertEquals(0, result.settings.hub.teleportDelaySeconds)
        assertEquals(30, result.settings.reset.moveTimeoutSeconds)
        assertEquals(2, result.issues.size)
    }
}
