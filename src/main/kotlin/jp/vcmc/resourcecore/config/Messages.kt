package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.logic.formatDuration
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.time.Instant

/** messages.yml の読み込みと MiniMessage への変換。不足しているキーは jar 内の既定値で補う。 */
class Messages(private val plugin: JavaPlugin) {
    private val mini = MiniMessage.miniMessage()

    @Volatile
    private var config = YamlConfiguration()

    fun reload() {
        val file = File(plugin.dataFolder, FILE_NAME)
        if (!file.exists()) plugin.saveResource(FILE_NAME, false)
        val loaded = YamlConfiguration()
        try {
            loaded.load(file)
        } catch (e: Exception) {
            plugin.logger.severe("$FILE_NAME を読み込めませんでした。既定のメッセージを使います: ${e.message}")
        }
        val defaults = plugin.getResource(FILE_NAME)?.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        }
        if (defaults != null) {
            val missing = defaults.getKeys(true).filter { !defaults.isConfigurationSection(it) && !loaded.contains(it, true) }
            if (missing.isNotEmpty()) {
                plugin.logger.warning("$FILE_NAME に不足しているキーを既定値で補います: ${missing.joinToString()}")
            }
            loaded.setDefaults(defaults)
        }
        config = loaded
    }

    fun raw(path: String): String = config.getString(path) ?: path

    fun parse(template: String, vararg args: Pair<String, String>): Component {
        val resolvers = TagResolver.builder()
            .resolver(Placeholder.parsed("prefix", config.getString("prefix") ?: ""))
        args.forEach { (name, value) -> resolvers.resolver(Placeholder.parsed(name, value)) }
        return try {
            mini.deserialize(template, resolvers.build())
        } catch (e: Exception) {
            plugin.logger.warning("MiniMessage の解析に失敗しました: '$template' (${e.message})")
            Component.text(template)
        }
    }

    fun text(path: String, vararg args: Pair<String, String>): Component = parse(raw(path), *args)

    fun send(target: Audience, path: String, vararg args: Pair<String, String>) {
        val template = raw(path)
        if (template.isBlank()) return
        target.sendMessage(parse(template, *args))
    }

    fun broadcast(server: Server, component: Component) {
        server.onlinePlayers.forEach { it.sendMessage(component) }
        server.consoleSender.sendMessage(component)
    }

    /** アイテム名用。インベントリのアイテム名は既定で斜体になるので打ち消す。 */
    fun itemText(path: String, vararg args: Pair<String, String>): Component =
        text(path, *args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    fun itemLore(path: String, vararg args: Pair<String, String>): List<Component> =
        config.getStringList(path).map {
            parse(it, *args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        }

    fun worldName(type: ResourceType): String = raw("world-name.${type.id}")

    fun duration(seconds: Int): String =
        formatDuration(seconds, raw("time.hour"), raw("time.minute"), raw("time.second"))

    fun dateTime(millis: Long?, settings: Settings): String {
        if (millis == null) return raw("time.never")
        return settings.dateFormat.format(Instant.ofEpochMilli(millis).atZone(settings.zone))
    }

    fun yesNo(value: Boolean): String = raw(if (value) "common.label-yes" else "common.label-no")

    private companion object {
        const val FILE_NAME = "messages.yml"
    }
}
