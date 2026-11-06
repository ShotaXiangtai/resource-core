package jp.vcmc.resourcecore.config

import jp.vcmc.resourcecore.i18n.Language
import jp.vcmc.resourcecore.i18n.Translations
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

/** 受け取る人の言語で中身が決まる文字列。プレースホルダーの値に使う。 */
fun interface Localized {
    fun resolve(language: Language): String
}

/**
 * 受け取る人の言語で文面を作る。
 * 日本語は messages.yml、ほかの言語は lang フォルダから読む。どちらも、不足しているキーは jar 内の既定値で補う。
 * プレースホルダーの値は文字列か Localized で渡す。
 */
class Messages(private val plugin: JavaPlugin) {
    private val mini = MiniMessage.miniMessage()
    private val translations = Translations(plugin)

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
        translations.reload()
    }

    fun raw(language: Language, path: String): String =
        translations.string(language, path) ?: config.getString(path) ?: path

    fun parse(language: Language, template: String, vararg args: Pair<String, Any>): Component {
        val resolvers = TagResolver.builder()
            .resolver(Placeholder.parsed("prefix", raw(language, "prefix")))
        args.forEach { (name, value) ->
            resolvers.resolver(Placeholder.parsed(name, if (value is Localized) value.resolve(language) else value.toString()))
        }
        return try {
            mini.deserialize(template, resolvers.build())
        } catch (e: Exception) {
            plugin.logger.warning("MiniMessage の解析に失敗しました: '$template' (${e.message})")
            Component.text(template)
        }
    }

    fun text(target: Audience, path: String, vararg args: Pair<String, Any>): Component {
        val language = Language.of(target)
        return parse(language, raw(language, path), *args)
    }

    fun send(target: Audience, path: String, vararg args: Pair<String, Any>) {
        val language = Language.of(target)
        val template = raw(language, path)
        if (template.isBlank()) return
        target.sendMessage(parse(language, template, *args))
    }

    /** 全員とコンソールへ、それぞれの言語で送る。 */
    fun broadcast(server: Server, path: String, vararg args: Pair<String, Any>) {
        server.onlinePlayers.forEach { send(it, path, *args) }
        send(server.consoleSender, path, *args)
    }

    /** config.yml に書かれた文面を全員へ送る。文面は 1 つだけなので、プレースホルダーの値だけがそれぞれの言語になる。 */
    fun broadcastTemplate(server: Server, template: String, vararg args: Pair<String, Any>) {
        server.onlinePlayers.forEach { it.sendMessage(parse(Language.of(it), template, *args)) }
        server.consoleSender.sendMessage(parse(Language.DEFAULT, template, *args))
    }

    /** アイテム名用。インベントリのアイテム名は既定で斜体になるので打ち消す。 */
    fun itemText(target: Audience, path: String, vararg args: Pair<String, Any>): Component =
        text(target, path, *args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    fun itemLore(target: Audience, path: String, vararg args: Pair<String, Any>): List<Component> {
        val language = Language.of(target)
        val lines = translations.stringList(language, path) ?: config.getStringList(path)
        return lines.map { parse(language, it, *args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) }
    }

    fun worldName(type: ResourceType): Localized = Localized { raw(it, "world-name.${type.id}") }

    fun duration(seconds: Int): Localized = Localized {
        formatDuration(seconds, raw(it, "time.hour"), raw(it, "time.minute"), raw(it, "time.second"), raw(it, "time.separator"))
    }

    fun dateTime(millis: Long?, settings: Settings): Localized = Localized {
        if (millis == null) raw(it, "time.never") else settings.dateFormat.format(Instant.ofEpochMilli(millis).atZone(settings.zone))
    }

    fun yesNo(value: Boolean): Localized = Localized { raw(it, if (value) "common.label-yes" else "common.label-no") }

    private companion object {
        const val FILE_NAME = "messages.yml"
    }
}
