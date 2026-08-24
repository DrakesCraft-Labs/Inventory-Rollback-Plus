package me.danjono.inventoryrollback.inventory;

import me.danjono.inventoryrollback.InventoryRollback;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Prevents a backup from one game modality overwriting another modality's inventory. */
public final class WorldGroupPolicy {
    public static final String BYPASS_PERMISSION = "inventoryrollbackplus.restore.cross-group";
    private static final Map<Inventory, String> BACKUP_WORLDS =
            Collections.synchronizedMap(new WeakHashMap<Inventory, String>());

    private WorldGroupPolicy() {}

    public static boolean mayRestore(Player staff, Player target, String backupWorld) {
        if (!InventoryRollback.getInstance().getConfig().getBoolean("world-group-safety.enabled", true)) return true;
        if (InventoryRollback.getInstance().getConfig().getBoolean("world-group-safety.allow-permission-bypass", false)
                && staff.hasPermission(BYPASS_PERMISSION)) return true;
        String currentWorld = target.getWorld().getName();
        String backupGroup = groupOf(backupWorld,
                InventoryRollback.getInstance().getConfig().getConfigurationSection("world-group-safety.groups"));
        String currentGroup = groupOf(currentWorld,
                InventoryRollback.getInstance().getConfig().getConfigurationSection("world-group-safety.groups"));
        if (backupGroup.equals(currentGroup)) return true;
        staff.sendMessage("§c[InventoryRollbackPlus] Restore blocked: backup belongs to §e" + backupGroup
                + "§c but the player is currently in §e" + currentGroup + "§c.");
        staff.sendMessage("§7Move the player to the matching modality first. Cross-modality restore requires §f"
                + BYPASS_PERMISSION + "§7.");
        return false;
    }

    /** Associates a backup GUI with its source world without retaining closed inventories forever. */
    public static void registerBackupInventory(Inventory inventory, String backupWorld) {
        if (inventory != null) BACKUP_WORLDS.put(inventory, backupWorld);
    }

    /** Applies the same modality boundary to direct item extraction from a backup GUI. */
    public static boolean mayExtract(Player staff, Inventory inventory) {
        String backupWorld = BACKUP_WORLDS.get(inventory);
        return backupWorld != null && mayRestore(staff, staff, backupWorld);
    }

    /** Resolves ordered regex rules; unmatched normal worlds remain in the configured fallback group. */
    static String groupOf(String world, ConfigurationSection groups) {
        if (world == null || world.trim().isEmpty()) return "unknown";
        if (groups != null) {
            for (String group : groups.getKeys(false)) {
                List<String> expressions = groups.getStringList(group);
                for (String expression : expressions) {
                    try {
                        if (Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(world).matches()) {
                            return group.toLowerCase(Locale.ROOT);
                        }
                    } catch (PatternSyntaxException error) {
                        InventoryRollback.getInstance().getLogger().warning(
                                "Invalid world-group-safety regex '" + expression + "': " + error.getMessage());
                    }
                }
            }
        }
        String builtInGroup = builtInGroupOf(world);
        if (builtInGroup != null) return builtInGroup;
        return InventoryRollback.getInstance().getConfig().getString("world-group-safety.fallback-group", "survival")
                .toLowerCase(Locale.ROOT);
    }

    /** Safe defaults keep old production configs protected before administrators add custom rules. */
    static String builtInGroupOf(String world) {
        if (world == null || world.trim().isEmpty()) return "unknown";
        Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        groups.put("laboratorio", Arrays.asList("^laboratorio(?:_.*)?$"));
        groups.put("clasico", Arrays.asList("^clasico(?:_.*)?$"));
        groups.put("skyblock", Arrays.asList("^bskyblock_world(?:_.*)?$"));
        groups.put("oneblock", Arrays.asList("^oneblock_world(?:_.*)?$"));
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            for (String expression : entry.getValue()) {
                if (Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(world).matches()) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
