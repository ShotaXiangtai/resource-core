package jp.vcmc.resourcecore.gui

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.ResourceType
import jp.vcmc.resourcecore.reset.CancelResult
import jp.vcmc.resourcecore.reset.StartResult
import jp.vcmc.resourcecore.world.WorldPhase
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 GUI。イベントはプレイヤーを所有するリージョンで発火する。
 * ワールド操作は ResetManager や WorldService がグローバルリージョンへ送るので、ここから直接は触らない。
 */
class AdminGui(private val plugin: ResourceCorePlugin) : Listener {
    private sealed interface Screen {
        data object Main : Screen
        data class Confirm(val type: ResourceType) : Screen
    }

    private class Holder(val screen: Screen) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private val refreshers = ConcurrentHashMap<UUID, ScheduledTask>()
    private val messages get() = plugin.messages

    fun open(player: Player) = open(player, Screen.Main)

    private fun open(player: Player, screen: Screen) {
        plugin.tasks.entity(player) {
            val holder = Holder(screen)
            val (size, title) = when (screen) {
                Screen.Main -> MAIN_SIZE to messages.text("gui.title")
                is Screen.Confirm -> CONFIRM_SIZE to messages.text("gui.confirm-title")
            }
            holder.inv = Bukkit.createInventory(holder, size, title)
            render(holder, player)
            player.openInventory(holder.inv)
            if (screen == Screen.Main) startRefresh(player) else stopRefresh(player.uniqueId)
        }
    }

    /** 残り時間や人数が変わるので、開いている間は毎秒描き直す。 */
    private fun startRefresh(player: Player) {
        stopRefresh(player.uniqueId)
        val task = plugin.tasks.entityTimer(
            player, 20, 20,
            retired = { refreshers.remove(player.uniqueId) },
        ) { task ->
            val holder = player.openInventory.topInventory.getHolder(false) as? Holder
            if (holder?.screen != Screen.Main) {
                task.cancel()
                refreshers.remove(player.uniqueId, task)
            } else {
                render(holder, player)
            }
        }
        if (task != null) refreshers[player.uniqueId] = task
    }

    private fun stopRefresh(uuid: UUID) {
        refreshers.remove(uuid)?.cancel()
    }

    private fun render(holder: Holder, player: Player) {
        when (val screen = holder.screen) {
            Screen.Main -> renderMain(holder.inv, player)
            is Screen.Confirm -> renderConfirm(holder.inv, screen.type)
        }
    }

    private fun renderMain(inv: Inventory, player: Player) {
        val settings = plugin.settings
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        for (slot in 0 until inv.size) inv.setItem(slot, filler)

        ResourceType.entries.forEach { type ->
            val world = plugin.worlds.world(type)
            val name = messages.worldName(type)
            val state = plugin.stateLabel(type)
            inv.setItem(
                statusSlot(type),
                item(
                    ICONS.getValue(type),
                    messages.itemText("gui.status-name", "world" to name),
                    messages.itemLore(
                        "gui.status-lore",
                        "key" to settings.world(type).key.asString(),
                        "state" to state,
                        "loaded" to messages.yesNo(world != null),
                        "players" to (world?.playerCount ?: 0).toString(),
                        "pregen" to plugin.pregen.label(settings.world(type).key),
                        "last_reset" to messages.dateTime(plugin.data.lastReset(settings.world(type).key), settings),
                    ),
                ),
            )
            inv.setItem(actionSlot(type), actionItem(type, state))
            val changeSeed = settings.world(type).changeSeed
            inv.setItem(
                seedSlot(type),
                item(
                    if (changeSeed) Material.LIME_DYE else Material.GRAY_DYE,
                    messages.itemText("gui.seed-name", "value" to messages.yesNo(changeSeed)),
                    messages.itemLore("gui.seed-lore"),
                ),
            )
            inv.setItem(
                teleportSlot(type),
                item(
                    Material.ENDER_PEARL,
                    messages.itemText("gui.teleport-name", "world" to name),
                    messages.itemLore("gui.teleport-lore"),
                ),
            )
        }
        inv.setItem(
            SLOT_MAIN,
            item(Material.COMPASS, messages.itemText("gui.main-name"), messages.itemLore("gui.main-lore")),
        )
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, messages.itemText("gui.close-name"), emptyList()))
    }

    private fun actionItem(type: ResourceType, state: String): ItemStack {
        val remaining = plugin.resets.remainingSeconds(type)
        return when {
            remaining != null -> item(
                Material.RED_CONCRETE,
                messages.itemText("gui.cancel-name"),
                messages.itemLore("gui.cancel-lore", "time" to messages.duration(remaining)),
            )
            plugin.worlds.phase(type) == WorldPhase.RESETTING || plugin.worlds.phase(type) == WorldPhase.CREATING -> item(
                Material.CLOCK,
                messages.itemText("gui.busy-name"),
                messages.itemLore("gui.busy-lore", "state" to state),
            )
            plugin.worlds.world(type) == null -> item(
                Material.LIME_CONCRETE,
                messages.itemText("gui.create-name"),
                messages.itemLore("gui.create-lore"),
            )
            else -> item(Material.TNT, messages.itemText("gui.reset-name"), messages.itemLore("gui.reset-lore"))
        }
    }

    private fun renderConfirm(inv: Inventory, type: ResourceType) {
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        for (slot in 0 until inv.size) inv.setItem(slot, filler)
        val name = messages.worldName(type)
        val duration = plugin.settings.reset.countdown.maxOfOrNull { it.seconds } ?: 0
        inv.setItem(
            SLOT_CONFIRM,
            item(
                Material.RED_WOOL,
                messages.itemText("gui.confirm-name"),
                messages.itemLore(
                    "gui.confirm-lore",
                    "world" to name,
                    "time" to messages.duration(duration),
                    "value" to messages.yesNo(plugin.settings.world(type).changeSeed),
                ),
            ),
        )
        inv.setItem(
            SLOT_CONFIRM_INFO,
            item(
                Material.PAPER,
                messages.itemText("gui.confirm-info-name", "world" to name),
                messages.itemLore("gui.confirm-info-lore"),
            ),
        )
        inv.setItem(SLOT_BACK, item(Material.LIME_WOOL, messages.itemText("gui.back-name"), emptyList()))
    }

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(name)
                meta.lore(lore)
            }
        }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        val holder = top.getHolder(false) as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != top) return
        if (!player.hasPermission(PERM_GUI)) {
            player.closeInventory()
            return
        }
        when (val screen = holder.screen) {
            Screen.Main -> onMainClick(player, event.slot)
            is Screen.Confirm -> onConfirmClick(player, screen.type, event.slot)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.getHolder(false) is Holder) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.getHolder(false) is Holder && event.reason != InventoryCloseEvent.Reason.OPEN_NEW) {
            stopRefresh(event.player.uniqueId)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = stopRefresh(event.player.uniqueId)

    private fun onMainClick(player: Player, slot: Int) {
        when (slot) {
            SLOT_MAIN -> {
                player.closeInventory()
                plugin.spawns.teleportToMain(player)
                return
            }
            SLOT_CLOSE -> {
                player.closeInventory()
                return
            }
        }
        ResourceType.entries.forEach { type ->
            when (slot) {
                actionSlot(type) -> onActionClick(player, type)
                seedSlot(type) -> toggleSeed(player, type)
                teleportSlot(type) -> {
                    player.closeInventory()
                    plugin.spawns.teleportToResource(player, type)
                }
            }
        }
    }

    private fun onActionClick(player: Player, type: ResourceType) {
        val name = messages.worldName(type)
        when {
            plugin.resets.remainingSeconds(type) != null -> {
                if (!requirePermission(player, PERM_CANCEL)) return
                val path = when (plugin.resets.cancel(type)) {
                    CancelResult.CANCELLED -> "reset.cancel-ok"
                    CancelResult.NOT_ACTIVE -> "reset.cancel-none"
                    CancelResult.TOO_LATE -> "reset.cancel-too-late"
                }
                messages.send(player, path, "world" to name)
            }
            plugin.worlds.phase(type) == WorldPhase.RESETTING -> messages.send(player, "resource.resetting", "world" to name)
            plugin.worlds.phase(type) == WorldPhase.CREATING -> Unit
            plugin.worlds.world(type) == null -> {
                if (requirePermission(player, PERM_CREATE)) plugin.createWorld(player, type)
            }
            else -> {
                if (requirePermission(player, PERM_RESET)) open(player, Screen.Confirm(type))
            }
        }
    }

    private fun toggleSeed(player: Player, type: ResourceType) {
        val next = !plugin.settings.world(type).changeSeed
        plugin.updateConfig("worlds.${type.id}.change-seed", next)
        messages.send(
            player, "admin.seed-toggled",
            "world" to messages.worldName(type),
            "value" to messages.yesNo(next),
        )
    }

    private fun onConfirmClick(player: Player, type: ResourceType, slot: Int) {
        when (slot) {
            SLOT_BACK -> open(player, Screen.Main)
            SLOT_CONFIRM -> {
                if (!requirePermission(player, PERM_RESET)) return
                val name = messages.worldName(type)
                when (plugin.resets.start(type, immediately = false)) {
                    StartResult.STARTED_COUNTDOWN -> messages.send(
                        player, "reset.accepted-countdown",
                        "world" to name,
                        "time" to messages.duration(plugin.resets.remainingSeconds(type) ?: 0),
                    )
                    StartResult.STARTED_NOW -> messages.send(player, "reset.accepted-now", "world" to name)
                    StartResult.SKIPPED_TO_NOW -> messages.send(player, "reset.skipped-to-now", "world" to name)
                    StartResult.ALREADY_ACTIVE -> messages.send(player, "reset.already-active", "world" to name)
                    StartResult.WORLD_NOT_LOADED -> messages.send(player, "reset.world-not-loaded", "world" to name)
                }
                open(player, Screen.Main)
            }
        }
    }

    private fun requirePermission(player: Player, node: String): Boolean {
        if (player.hasPermission(node)) return true
        messages.send(player, "common.no-permission")
        return false
    }

    private companion object {
        const val MAIN_SIZE = 54
        const val CONFIRM_SIZE = 27

        const val SLOT_MAIN = 47
        const val SLOT_CLOSE = 51
        const val SLOT_CONFIRM = 11
        const val SLOT_CONFIRM_INFO = 13
        const val SLOT_BACK = 15

        const val PERM_GUI = "resourcecore.admin.gui"
        const val PERM_RESET = "resourcecore.admin.reset"
        const val PERM_CANCEL = "resourcecore.admin.cancel"
        const val PERM_CREATE = "resourcecore.admin.create"

        val ICONS = mapOf(
            ResourceType.OVERWORLD to Material.GRASS_BLOCK,
            ResourceType.NETHER to Material.NETHERRACK,
            ResourceType.END to Material.END_STONE,
        )

        // 種類ごとに 1 列使う。オーバーワールド: 2 列目、ネザー: 5 列目、エンド: 8 列目。
        fun statusSlot(type: ResourceType) = 10 + 3 * type.ordinal
        fun actionSlot(type: ResourceType) = 19 + 3 * type.ordinal
        fun seedSlot(type: ResourceType) = 28 + 3 * type.ordinal
        fun teleportSlot(type: ResourceType) = 37 + 3 * type.ordinal
    }
}
