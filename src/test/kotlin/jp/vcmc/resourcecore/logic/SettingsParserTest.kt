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
        assertEquals(10_000, settings.borderRadius)
        assertTrue(settings.pregenEnabled)
        assertEquals(RelocateTarget.MAIN_WORLD, settings.relocateTarget)
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
    fun `config files from 1_0_0 still load`() {
        val result = parse("login:\n  relocate-target: hub\nhub:\n  world: 'lobby:hub'\n")
        assertEquals(RelocateTarget.MAIN_WORLD, result.settings.relocateTarget)
        assertTrue(result.issues.isEmpty(), result.issues.toString())
    }

    @Test
    fun `invalid relocate target falls back with an issue`() {
        val result = parse("login:\n  relocate-target: moon\n")
        assertEquals(RelocateTarget.MAIN_WORLD, result.settings.relocateTarget)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `invalid border radius falls back with an issue`() {
        val zero = parse("border:\n  radius: 0\n")
        assertEquals(10_000, zero.settings.borderRadius)
        assertEquals("border.radius", zero.issues.single().path)
        assertEquals(10_000, parse("border:\n  radius: 40000000\n").settings.borderRadius)
        assertEquals(5_000, parse("border:\n  radius: 5000\n").settings.borderRadius)
    }

    @Test
    fun `invalid timezone and date format fall back`() {
        val result = parse("display:\n  timezone: Mars/Base\n  date-format: 'qqqq{{'\n")
        assertEquals("Asia/Tokyo", result.settings.zone.id)
        assertEquals(2, result.issues.size)
    }

    @Test
    fun `invalid move timeout falls back`() {
        val result = parse("reset:\n  move-timeout-seconds: 0\n")
        assertEquals(30, result.settings.reset.moveTimeoutSeconds)
        assertEquals(1, result.issues.size)
    }
}
