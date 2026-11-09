package jp.vcmc.resourcecore.i18n

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * 日本語以外の文面。lang/<言語>.yml から読み、ファイルに無いキーは jar 内の既定値で補う。
 * 日本語の文面はこれまでどおりの場所にあるので、ここでは扱わない（string は null を返す）。
 */
class Translations(private val plugin: JavaPlugin) {
    @Volatile
    private var files: Map<Language, YamlConfiguration> = emptyMap()

    /** 以前のリリースの既定の文面。ファイル名ごとのハッシュ。 */
    private val outdated: Map<String, Set<String>> by lazy {
        plugin.getResource(OUTDATED)?.use { OutdatedLangFiles.parse(String(it.readBytes(), Charsets.UTF_8)) }.orEmpty()
    }

    /** lang フォルダを読み直す。無いファイルは jar からコピーする。 */
    fun reload() {
        files = Language.entries.filter { it != Language.JAPANESE }.associateWith(::load)
    }

    fun string(language: Language, path: String): String? = files[language]?.getString(path)

    fun stringList(language: Language, path: String): List<String>? =
        files[language]?.takeIf { it.isList(path) }?.getStringList(path)

    private fun load(language: Language): YamlConfiguration {
        val name = "${language.code}.yml"
        val resource = "lang/$name"
        val file = File(plugin.dataFolder, resource)
        try {
            OutdatedLangFiles.moveAside(file, outdated[name].orEmpty())?.let { backup ->
                plugin.logger.info("$resource は以前の既定の文面のままだったので、新しい文面に置き換えます。以前の文面は lang/${backup.name} に残します。")
            }
        } catch (e: IOException) {
            plugin.logger.warning("$resource を新しい文面に置き換えられませんでした: ${e.message}")
        }
        if (!file.exists()) plugin.saveResource(resource, false)
        val loaded = YamlConfiguration()
        try {
            loaded.load(file)
        } catch (e: Exception) {
            plugin.logger.severe("$resource を読み込めませんでした。既定の文面を使います: ${e.message}")
        }
        plugin.getResource(resource)?.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).use { loaded.setDefaults(YamlConfiguration.loadConfiguration(it)) }
        }
        return loaded
    }

    private companion object {
        const val OUTDATED = "lang/outdated.txt"
    }
}
