package me.danjono.inventoryrollback.gui.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.PlayerData;
import me.danjono.inventoryrollback.gui.Buttons;
import me.danjono.inventoryrollback.gui.InventoryName;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;

public class PlayerMenu {

    private Player staff;
    private OfflinePlayer offlinePlayer;
    private String worldGroup;

    private Buttons buttons;
    private Inventory inventory;

    public PlayerMenu(Player staff, OfflinePlayer player, String worldGroup) {
        this.staff = staff;
        this.offlinePlayer = player;
        this.worldGroup = worldGroup;
        this.buttons = new Buttons(player.getUniqueId());

        createInventory();
    }

    public void createInventory() {
        inventory = Bukkit.createInventory(staff, InventoryName.PLAYER_MENU.getSize(), InventoryName.PLAYER_MENU.getName());
        
        inventory.setItem(2, WorldGroupPolicy.tagGroup(buttons.createDeathLogButton(LogType.DEATH, null), worldGroup));
        inventory.setItem(3, WorldGroupPolicy.tagGroup(buttons.createJoinLogButton(LogType.JOIN, null), worldGroup));
        inventory.setItem(4, WorldGroupPolicy.tagGroup(buttons.createQuitLogButton(LogType.QUIT, null), worldGroup));
        inventory.setItem(5, WorldGroupPolicy.tagGroup(buttons.createWorldChangeLogButton(LogType.WORLD_CHANGE, null), worldGroup));
        inventory.setItem(6, WorldGroupPolicy.tagGroup(buttons.createForceSaveLogButton(LogType.FORCE, null), worldGroup));
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    public void getPlayerMenu() {
        List<String> lore = new ArrayList<>();
        
        if (offlinePlayer.isOnline()) {
            lore.add(ChatColor.GREEN + "Online now");
        } else if (!offlinePlayer.hasPlayedBefore()) {
            lore.add(ChatColor.RED + "Never played on this server");
        } else {
            lore.add(ChatColor.RED + "Offline");
            
            String dateTime = "Unknown";
            if (offlinePlayer.getLastPlayed() != 0)
                dateTime = PlayerData.getTime(offlinePlayer.getLastPlayed());
            lore.add(ChatColor.RED + "Last online: " + dateTime);
        }
        
        inventory.setItem(0, buttons.playerHead(lore, true));
        UUID uuid = offlinePlayer.getUniqueId();

        PlayerData deathBackup = new PlayerData(uuid, LogType.DEATH, null);
        PlayerData joinBackup = new PlayerData(uuid, LogType.JOIN, null);
        PlayerData quitBackup = new PlayerData(uuid, LogType.QUIT, null);
        PlayerData worldChangeBackup = new PlayerData(uuid, LogType.WORLD_CHANGE, null);
        PlayerData forceSaveBackup = new PlayerData(uuid, LogType.FORCE, null);

        int deathCount = deathBackup.getTimestampsForGroup(worldGroup).size();
        int joinCount = joinBackup.getTimestampsForGroup(worldGroup).size();
        int quitCount = quitBackup.getTimestampsForGroup(worldGroup).size();
        int worldChangeCount = worldChangeBackup.getTimestampsForGroup(worldGroup).size();
        int forceCount = forceSaveBackup.getTimestampsForGroup(worldGroup).size();

        if (deathCount + joinCount + quitCount + worldChangeCount + forceCount == 0) {

            //No backups have been found for the player
            staff.sendMessage(MessageData.getPluginPrefix() + MessageData.getNoBackupError(offlinePlayer.getName()));
        }
        
        String backupsAvailable = " backup(s) available";

        List<String> deaths = Arrays.asList(deathCount + backupsAvailable);
        inventory.setItem(2, WorldGroupPolicy.tagGroup(buttons.createDeathLogButton(LogType.DEATH, deaths), worldGroup));
        
        List<String> joins = Arrays.asList(joinCount + backupsAvailable);
        inventory.setItem(3, WorldGroupPolicy.tagGroup(buttons.createJoinLogButton(LogType.JOIN, joins), worldGroup));
        
        List<String> quits = Arrays.asList(quitCount + backupsAvailable);
        inventory.setItem(4, WorldGroupPolicy.tagGroup(buttons.createQuitLogButton(LogType.QUIT, quits), worldGroup));
        
        List<String> worldChange = Arrays.asList(worldChangeCount + backupsAvailable);
        inventory.setItem(5, WorldGroupPolicy.tagGroup(buttons.createWorldChangeLogButton(LogType.WORLD_CHANGE, worldChange), worldGroup));
        
        List<String> forceSaves = Arrays.asList(forceCount + backupsAvailable);
        inventory.setItem(6, WorldGroupPolicy.tagGroup(buttons.createForceSaveLogButton(LogType.FORCE, forceSaves), worldGroup));
    }

}
