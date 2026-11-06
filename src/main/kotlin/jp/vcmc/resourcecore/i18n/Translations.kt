package jp.vcmc.resourcecore.i18n

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader

/**
 * 日本語以外の文面。lang/<言語>.yml から読み、ファイルに無いキーは jar 内の既定値で補う。
 * 日本語の文面はこれまでどおりの場所にあるので、ここでは扱わない（string は null を返す）。
 */
class Translations(private val plugin: JavaPlugin) {
    @Volatile
    private var files: Map<Language, YamlConfiguration> = emptyMap()

    /** lang フォルダを読み直す。無いファイルは jar からコピーする。 */
    fun reload() {
        files = Language.entries.filter { it != Language.JAPANESE }.associateWith(::load)
    }

    fun string(language: Language, path: String): String? = files[language]?.getString(path)

    fun stringList(language: Language, path: String): List<String>? =
        files[language]?.takeIf { it.isList(path) }?.getStringList(path)

    private fun load(language: Language): YamlConfiguration {
        val resource = "lang/${language.code}.yml"
        val file = File(plugin.dataFolder, resource)
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
}
