package me.danjono.inventoryrollback.inventory;

import me.danjono.inventoryrollback.InventoryRollback;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Prevents a backup from one game modality overwriting another modality's inventory. */
public final class WorldGroupPolicy {
    public static final String BYPASS_PERMISSION = "inventoryrollbackplus.restore.cross-group";

    private WorldGroupPolicy() {}

    public static boolean mayRestore(Player staff, Player target, String backupWorld) {
        if (!InventoryRollback.getInstance().getConfig().getBoolean("world-group-safety.enabled", true)) return true;
        if (staff.hasPermission(BYPASS_PERMISSION)) return true;
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

    /** Resolves ordered regex rules; unmatched normal worlds remain in the configured fallback group. */
    static String groupOf(String world, ConfigurationSection groups) {
        if (world == null || world.trim().isEmpty()) return "unknown";
        String normalized = world.toLowerCase(Locale.ROOT);
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
        return InventoryRollback.getInstance().getConfig().getString("world-group-safety.fallback-group", "survival")
                .toLowerCase(Locale.ROOT);
    }
}
