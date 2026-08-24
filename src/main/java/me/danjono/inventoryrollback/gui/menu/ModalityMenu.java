package me.danjono.inventoryrollback.gui.menu;

import com.nuclyon.technicallycoded.inventoryrollback.customdata.CustomDataItemEditor;
import me.danjono.inventoryrollback.gui.Buttons;
import me.danjono.inventoryrollback.gui.InventoryName;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.UUID;

/** First restore step: select the inventory modality before selecting a backup event. */
public final class ModalityMenu {
    private final Inventory inventory;
    private final UUID targetUuid;

    public ModalityMenu(Player staff, OfflinePlayer target) {
        targetUuid = target.getUniqueId();
        inventory = Bukkit.createInventory(staff, InventoryName.MODALITY_MENU.getSize(), InventoryName.MODALITY_MENU.getName());
        Buttons buttons = new Buttons(target);
        inventory.setItem(0, buttons.playerHead(Arrays.asList(ChatColor.GRAY + "Back to players"), true));
        addModality(2, Material.GRASS_BLOCK, "survival", "Survival");
        addModality(3, Material.BREWING_STAND, "laboratorio", "Laboratory");
        addModality(4, Material.CRAFTING_TABLE, "clasico", "Classic");
        addModality(5, Material.GRASS_BLOCK, "skyblock", "SkyBlock");
        addModality(6, Material.OBSERVER, "oneblock", "OneBlock");
    }

    private void addModality(int slot, Material material, String group, String label) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(ChatColor.AQUA + label);
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Show only backups from this modality"));
        item.setItemMeta(meta);
        item = WorldGroupPolicy.tagGroup(item, group);
        CustomDataItemEditor editor = CustomDataItemEditor.editItem(item);
        editor.setString("uuid", targetUuid.toString());
        inventory.setItem(slot, editor.setItemData());
    }

    public Inventory getInventory() {
        return inventory;
    }
}
