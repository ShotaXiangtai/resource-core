package jp.vcmc.resourcecore.gui

import jp.vcmc.resourcecore.ResourceCorePlugin
import jp.vcmc.resourcecore.logic.ResourceType
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** プレイヤー向けの移動 GUI。/resource を引数なしで実行すると開く。 */
class TravelGui(private val plugin: ResourceCorePlugin) : Listener {
    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private val messages get() = plugin.messages

    fun open(player: Player) {
        plugin.tasks.entity(player) {
            val holder = Holder()
            holder.inv = Bukkit.createInventory(holder, SIZE, messages.text("travel-gui.title"))
            render(holder.inv, player)
            player.openInventory(holder.inv)
        }
    }

    /** ボタンを押すとどこへ行くかの表示。実際の移動先の決め方は SpawnService.teleportToResource と同じ。 */
    private fun destination(player: Player, type: ResourceType): String {
        val world = plugin.worlds.world(type)
        val last = world?.takeIf { player.world != it }?.let { plugin.spawns.lastLocationIn(player.uniqueId, it) }
            ?: return messages.raw("travel-gui.destination-spawn")
        return messages.raw("travel-gui.destination-last")
            .replace("<x>", last.blockX.toString())
            .replace("<y>", last.blockY.toString())
            .replace("<z>", last.blockZ.toString())
    }

    private fun render(inv: Inventory, player: Player) {
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        for (slot in 0 until inv.size) inv.setItem(slot, filler)
        inv.setItem(
            SLOT_MAIN,
            item(Material.COMPASS, messages.itemText("travel-gui.main-name"), messages.itemLore("travel-gui.main-lore")),
        )
        ResourceType.entries.forEach { type ->
            inv.setItem(
                slotOf(type),
                item(
                    ICONS.getValue(type),
                    messages.itemText("travel-gui.world-name", "world" to messages.worldName(type)),
                    messages.itemLore(
                        "travel-gui.world-lore",
                        "state" to plugin.stateLabel(type),
                        "players" to (plugin.worlds.world(type)?.playerCount ?: 0).toString(),
                        "destination" to destination(player, type),
                    ),
                ),
            )
        }
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
        if (top.getHolder(false) !is Holder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != top) return
        if (event.slot == SLOT_MAIN) {
            closeThen(player) { if (player.hasPermission(PERM_MAIN)) plugin.spawns.teleportToMain(player) }
            return
        }
        val type = ResourceType.entries.firstOrNull { slotOf(it) == event.slot } ?: return
        closeThen(player) { if (player.hasPermission(PERM_RESOURCE)) plugin.spawns.teleportToResource(player, type) }
    }

    /** InventoryClickEvent の処理中にインベントリを閉じてはいけないので、次の tick へ送る。 */
    private fun closeThen(player: Player, block: () -> Unit) {
        plugin.tasks.entity(player) {
            player.closeInventory()
            block()
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.getHolder(false) is Holder) event.isCancelled = true
    }

    private companion object {
        const val SIZE = 27
        const val SLOT_MAIN = 10
        const val PERM_MAIN = "resourcecore.command.main"
        const val PERM_RESOURCE = "resourcecore.command.resource"

        val ICONS = mapOf(
            ResourceType.OVERWORLD to Material.GRASS_BLOCK,
            ResourceType.NETHER to Material.NETHERRACK,
            ResourceType.END to Material.END_STONE,
        )

        fun slotOf(type: ResourceType) = 12 + 2 * type.ordinal
    }
}
