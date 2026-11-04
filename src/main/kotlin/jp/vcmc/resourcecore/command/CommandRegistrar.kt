package jp.vcmc.resourcecore.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.reset.CancelResult
import jp.vcmc.resourcecore.reset.StartResult
import jp.vcmc.resourcecore.world.WorldPhase
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Brigadier でコマンドを登録する。 */
class CommandRegistrar(private val plugin: ResourceCorePlugin) {
    private val messages get() = plugin.messages

    fun register() {
        plugin.lifecycleManager.registerEventHandler(
            LifecycleEvents.COMMANDS.newHandler { event ->
                val commands = event.registrar()
                commands.register(hub().build(), "ハブへ移動します")
                commands.register(resource().build(), "資源ワールドへ移動します")
                commands.register(admin().build(), "resource-core の管理コマンド", listOf("rc"))
            },
        )
    }

    private fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    private fun literal(name: String, node: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires(perm(node))

    private fun player(context: CommandContext<CommandSourceStack>): Player? {
        val source = context.source
        return source.executor as? Player ?: source.sender as? Player
    }

    /** 引数なし・確認なしで即実行する。メニュープラグインから呼ぶ想定。 */
    private fun hub() = literal("hub", "resourcecore.command.hub").executes { context ->
        val player = player(context)
        if (player == null) messages.send(context.source.sender, "common.player-only") else plugin.hub.requestTeleport(player)
        Command.SINGLE_SUCCESS
    }

    private fun resource(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = literal("resource", "resourcecore.command.resource").executes { teleportToResource(it, ResourceType.OVERWORLD) }
        ResourceType.entries.forEach { type ->
            root.then(Commands.literal(type.id).executes { teleportToResource(it, type) })
        }
        return root
    }

    private fun teleportToResource(context: CommandContext<CommandSourceStack>, type: ResourceType): Int {
        val player = player(context)
        if (player == null) messages.send(context.source.sender, "common.player-only") else plugin.spawns.teleportToResource(player, type)
        return Command.SINGLE_SUCCESS
    }

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("resource-core").requires { source ->
            ADMIN_NODES.any { source.sender.hasPermission(it) }
        }
        root.then(reset())
        root.then(cancel())
        root.then(create())
        root.then(literal("status", "resourcecore.admin.status").executes { status(it.source.sender) })
        root.then(literal("reload", "resourcecore.admin.reload").executes { reload(it.source.sender) })
        root.then(
            literal("admin", "resourcecore.admin.gui").executes { context ->
                val player = player(context)
                if (player == null) messages.send(context.source.sender, "common.player-only") else plugin.gui.open(player)
                Command.SINGLE_SUCCESS
            },
        )
        root.then(
            literal("sethub", "resourcecore.admin.sethub").executes { context ->
                val player = player(context)
                if (player == null) messages.send(context.source.sender, "common.player-only") else plugin.hub.setHub(player, player.world)
                Command.SINGLE_SUCCESS
            },
        )
        return root
    }

    private fun reset(): LiteralArgumentBuilder<CommandSourceStack> {
        val node = literal("reset", "resourcecore.admin.reset")
        ResourceType.entries.forEach { type ->
            node.then(
                Commands.literal(type.id)
                    .executes { startReset(it.source.sender, type, immediately = false) }
                    .then(Commands.literal("--now").executes { startReset(it.source.sender, type, immediately = true) }),
            )
        }
        return node
    }

    private fun startReset(sender: CommandSender, type: ResourceType, immediately: Boolean): Int {
        val name = messages.worldName(type)
        when (plugin.resets.start(type, immediately)) {
            StartResult.STARTED_COUNTDOWN -> messages.send(
                sender, "reset.accepted-countdown",
                "world" to name,
                "time" to messages.duration(plugin.resets.remainingSeconds(type) ?: 0),
            )
            StartResult.STARTED_NOW -> messages.send(sender, "reset.accepted-now", "world" to name)
            StartResult.SKIPPED_TO_NOW -> messages.send(sender, "reset.skipped-to-now", "world" to name)
            StartResult.ALREADY_ACTIVE -> messages.send(sender, "reset.already-active", "world" to name)
            StartResult.WORLD_NOT_LOADED -> messages.send(sender, "reset.world-not-loaded", "world" to name)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun cancel(): LiteralArgumentBuilder<CommandSourceStack> {
        val node = literal("cancel", "resourcecore.admin.cancel")
        ResourceType.entries.forEach { type ->
            node.then(Commands.literal(type.id).executes { context ->
                val sender = context.source.sender
                val name = messages.worldName(type)
                when (plugin.resets.cancel(type)) {
                    CancelResult.CANCELLED -> messages.send(sender, "reset.cancel-ok", "world" to name)
                    CancelResult.NOT_ACTIVE -> messages.send(sender, "reset.cancel-none", "world" to name)
                    CancelResult.TOO_LATE -> messages.send(sender, "reset.cancel-too-late", "world" to name)
                }
                Command.SINGLE_SUCCESS
            })
        }
        return node
    }

    private fun create(): LiteralArgumentBuilder<CommandSourceStack> {
        val node = literal("create", "resourcecore.admin.create")
        ResourceType.entries.forEach { type ->
            node.then(Commands.literal(type.id).executes { context ->
                plugin.createWorld(context.source.sender, type)
                Command.SINGLE_SUCCESS
            })
        }
        return node
    }

    private fun status(sender: CommandSender): Int {
        val settings = plugin.settings
        messages.send(sender, "admin.status-header")
        ResourceType.entries.forEach { type ->
            val world = plugin.worlds.world(type)
            messages.send(
                sender, "admin.status-world",
                "world" to messages.worldName(type),
                "key" to settings.world(type).key.asString(),
                "state" to plugin.stateLabel(type),
            )
            messages.send(
                sender, "admin.status-world-detail",
                "loaded" to messages.yesNo(world != null),
                "players" to (world?.playerCount ?: 0).toString(),
                "spawn" to (world?.spawnLocation?.let { "${it.blockX}, ${it.blockY}, ${it.blockZ}" } ?: "-"),
                "last_reset" to messages.dateTime(plugin.data.lastReset(settings.world(type).key), settings),
                "change_seed" to messages.yesNo(settings.world(type).changeSeed),
            )
        }
        messages.send(sender, "admin.status-hub", "hub" to (settings.hub.key?.asString() ?: messages.raw("admin.status-hub-unset")))
        return Command.SINGLE_SUCCESS
    }

    private fun reload(sender: CommandSender): Int {
        if (plugin.resets.isBusy() || ResourceType.entries.any { plugin.worlds.phase(it) == WorldPhase.RESETTING }) {
            messages.send(sender, "admin.reload-busy")
            return Command.SINGLE_SUCCESS
        }
        val issues = plugin.reloadAll()
        if (issues.isEmpty()) {
            messages.send(sender, "admin.reload-ok")
        } else {
            messages.send(sender, "admin.reload-issues", "count" to issues.size.toString())
        }
        return Command.SINGLE_SUCCESS
    }

    private companion object {
        val ADMIN_NODES = listOf(
            "resourcecore.admin.reset",
            "resourcecore.admin.cancel",
            "resourcecore.admin.status",
            "resourcecore.admin.reload",
            "resourcecore.admin.gui",
            "resourcecore.admin.sethub",
            "resourcecore.admin.create",
        )
    }
}
