package jp.vcmc.resourcecore

import jp.vcmc.resourcecore.command.CommandRegistrar
import jp.vcmc.resourcecore.config.ConfigIssue
import jp.vcmc.resourcecore.config.DataStore
import jp.vcmc.resourcecore.config.Messages
import jp.vcmc.resourcecore.config.Settings
import jp.vcmc.resourcecore.config.SettingsParser
import jp.vcmc.resourcecore.gui.AdminGui
import jp.vcmc.resourcecore.gui.TravelGui
import jp.vcmc.resourcecore.listener.CanvasPortalListener
import jp.vcmc.resourcecore.listener.LoginListener
import jp.vcmc.resourcecore.listener.PortalListener
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.reset.ResetManager
import jp.vcmc.resourcecore.util.Tasks
import jp.vcmc.resourcecore.util.writeAtomically
import jp.vcmc.resourcecore.world.PregenService
import jp.vcmc.resourcecore.world.SpawnService
import jp.vcmc.resourcecore.world.WorldPhase
import jp.vcmc.resourcecore.world.WorldService
import net.kyori.adventure.key.Key
import org.bukkit.GameRules
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ResourceCorePlugin : JavaPlugin() {
    lateinit var tasks: Tasks
        private set
    lateinit var messages: Messages
        private set
    lateinit var data: DataStore
        private set
    lateinit var worlds: WorldService
        private set
    lateinit var spawns: SpawnService
        private set
    lateinit var resets: ResetManager
        private set
    lateinit var pregen: PregenService
        private set
    lateinit var gui: AdminGui
        private set
    lateinit var travelGui: TravelGui
        private set

    @Volatile
    lateinit var settings: Settings
        private set

    private val configLock = Any()

    override fun onEnable() {
        saveDefaultConfig()
        tasks = Tasks(this)
        messages = Messages(this)
        data = DataStore(File(dataFolder, "data.yml"), logger, tasks::async)
        data.load()
        worlds = WorldService(this)
        spawns = SpawnService(this)
        resets = ResetManager(this)
        pregen = PregenService(this)
        gui = AdminGui(this)
        travelGui = TravelGui(this)
        loadConfiguration()
        pregen.connect()

        val pluginManager = server.pluginManager
        pluginManager.registerEvents(PortalListener(this), this)
        pluginManager.registerEvents(LoginListener(this), this)
        pluginManager.registerEvents(gui, this)
        pluginManager.registerEvents(travelGui, this)
        if (isCanvas()) {
            pluginManager.registerEvents(CanvasPortalListener(this), this)
            logger.info("CanvasMC を検出しました。ポータルの行き先を確定するリスナーを有効にします")
        }
        CommandRegistrar(this).register()

        // ワールドの準備はサーバーが動き始めてからグローバルリージョンで行う。
        tasks.global {
            restoreLegacyHubRules()
            worlds.ensureAll()
        }
    }

    override fun onDisable() {
        if (::resets.isInitialized) resets.shutdown()
        if (::data.isInitialized) data.saveNow()
    }

    /** 1.0.0 のハブ保護が変更したゲームルールを元に戻す。ハブは廃止したので、そのまま残さない。 */
    private fun restoreLegacyHubRules() {
        val record = data.legacyHubRules ?: return
        val world = runCatching { Key.key(record.world) }.getOrNull()?.let { server.getWorld(it) }
        if (world == null) {
            logger.warning("以前のハブ ${record.world} が読み込まれていないため、ゲームルールを元に戻せませんでした: ${record.originals}")
            return
        }
        listOf(GameRules.PVP, GameRules.FALL_DAMAGE, GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS).forEach { rule ->
            record.originals[rule.key().asString()]?.let { world.setGameRule(rule, it) }
        }
        data.legacyHubRules = null
        data.saveAsync()
        logger.info("以前のハブ ${record.world} のゲームルールを元に戻しました")
    }

    /** config.yml と messages.yml を再読み込みして、ワールドへ反映する。 */
    fun reloadAll(): List<ConfigIssue> {
        val issues = loadConfiguration()
        tasks.global { worlds.ensureAll() }
        return issues
    }

    private fun loadConfiguration(): List<ConfigIssue> {
        val parsed = synchronized(configLock) {
            reloadConfig()
            SettingsParser.parse(config)
        }
        parsed.issues.forEach { logger.severe("[config.yml] ${it.path}: ${it.message}") }
        settings = parsed.settings
        messages.reload()
        return parsed.issues
    }

    /** GUI などから設定を変更する。config.yml のコメントは保持される。 */
    fun updateConfig(path: String, value: Any) {
        val content = synchronized(configLock) {
            config.set(path, value)
            settings = SettingsParser.parse(config).settings
            config.saveToString()
        }
        val file = File(dataFolder, "config.yml").toPath()
        tasks.async {
            synchronized(configLock) {
                try {
                    writeAtomically(file, content)
                } catch (e: Exception) {
                    logger.severe("config.yml を保存できませんでした: ${e.message}")
                }
            }
        }
    }

    /** 未作成のワールドを作成する。GUI とコマンドの共通処理。 */
    fun createWorld(sender: CommandSender, type: ResourceType) {
        val name = messages.worldName(type)
        if (worlds.world(type) != null) {
            messages.send(sender, "create.already-exists", "world" to name)
            return
        }
        messages.send(sender, "create.started", "world" to name)
        tasks.global {
            worlds.ensure(type).whenComplete { _, error ->
                messages.send(sender, if (error == null) "create.finished" else "create.failed", "world" to name)
                tasks.global { worlds.validateRoutes() }
            }
        }
    }

    fun stateLabel(type: ResourceType): String {
        val remaining = resets.remainingSeconds(type)
        if (remaining != null) {
            return messages.raw("state.countdown").replace("<time>", messages.duration(remaining))
        }
        return when (worlds.phase(type)) {
            WorldPhase.MISSING -> messages.raw("state.missing")
            WorldPhase.CREATING -> messages.raw("state.creating")
            WorldPhase.READY -> messages.raw("state.ready")
            WorldPhase.RESETTING -> messages.raw("state.resetting")
        }
    }

    private fun isCanvas(): Boolean = try {
        Class.forName("io.canvasmc.canvas.event.EntityPortalAsyncEvent")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
