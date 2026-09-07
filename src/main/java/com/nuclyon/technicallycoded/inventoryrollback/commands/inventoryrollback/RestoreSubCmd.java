package com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import com.nuclyon.technicallycoded.inventoryrollback.commands.IRPCommand;
import com.nuclyon.technicallycoded.inventoryrollback.restore.BackupEntry;
import com.nuclyon.technicallycoded.inventoryrollback.restore.BackupQueryUtil;
import com.nuclyon.technicallycoded.inventoryrollback.restore.PendingRestoreManager;
import com.nuclyon.technicallycoded.inventoryrollback.restore.PlayerResolver;
import me.danjono.inventoryrollback.InventoryRollback;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.config.SoundData;
import me.danjono.inventoryrollback.data.PlayerData;
import me.danjono.inventoryrollback.gui.menu.MainMenu;
import me.danjono.inventoryrollback.gui.menu.ModalityMenu;
import me.danjono.inventoryrollback.inventory.RestoreInventory;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;
import me.danjono.inventoryrollback.data.LogType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RestoreSubCmd extends IRPCommand {

    /** Solo lectura: listar respaldos y abrir menus. Declarado en plugin.yml como "can't ... restore". */
    public static final String PERM_VIEW = "inventoryrollbackplus.viewbackups";
    /** Mutacion: aplicar restauraciones, encolarlas o cancelarlas. */
    public static final String PERM_RESTORE = "inventoryrollbackplus.restore";

    public RestoreSubCmd(InventoryRollbackPlus mainIn) {
        super(mainIn);
    }

    /** Entrada al comando: basta con poder mirar. La consola siempre pasa. */
    public static boolean mayView(boolean isPlayer, boolean hasView, boolean hasRestore) {
        return !isPlayer || hasView || hasRestore;
    }

    /** Toda accion que altera el inventario de un jugador exige el permiso de restauracion completo. */
    public static boolean mayMutate(boolean isPlayer, boolean hasRestore) {
        return !isPlayer || hasRestore;
    }

    /** --force salta la barrera entre modalidades: exige el permiso de cruce explicito. */
    public static boolean mayForce(boolean isPlayer, boolean hasCrossGroup) {
        return !isPlayer || hasCrossGroup;
    }

    @Override
    public void onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!mayView(sender instanceof Player, sender.hasPermission(PERM_VIEW), sender.hasPermission(PERM_RESTORE))) {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNoPermission());
            return;
        }

        if (!ConfigData.isEnabled()) {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getPluginDisabled());
            return;
        }

        handleRestore(sender, args);
    }

    private void handleRestore(CommandSender sender, String[] args) {
        // /irp restore
        if (args.length <= 1) {
            if (sender instanceof Player) {
                openMainMenu((Player) sender);
            } else {
                sendCliHelp(sender);
            }
            return;
        }

        String targetArg = args[1].trim();

        // Subcommand: pending / list-pending
        if (targetArg.equalsIgnoreCase("pending") || targetArg.equalsIgnoreCase("list-pending")) {
            listPendingRestores(sender);
            return;
        }

        // Subcommand: cancel-pending <player>
        if (targetArg.equalsIgnoreCase("cancel-pending")) {
            if (args.length < 3) {
                sender.sendMessage("§c[IRP] Uso: /irp restore cancel-pending <jugador>");
                return;
            }
            cancelPendingRestore(sender, args[2]);
            return;
        }

        // Player resolution using secure PlayerResolver
        Optional<PlayerResolver.ResolvedPlayer> optResolved = PlayerResolver.resolvePlayer(targetArg);
        if (!optResolved.isPresent()) {
            sender.sendMessage("§c[IRP] No se encontró ningún jugador registrado en el servidor para: §e" + targetArg);
            sender.sendMessage("§7El resolver validó usercache.json, BentoBox y respaldos existentes de IRP.");
            sender.sendMessage("§7Por seguridad (INC-056 / Ticket #344) no se generan UUIDs offline artificiales.");
            return;
        }

        PlayerResolver.ResolvedPlayer resolved = optResolved.get();

        // /irp restore <player> (no extra arguments)
        if (args.length == 2) {
            if (sender instanceof Player) {
                openPlayerMenu((Player) sender, resolved.getOfflinePlayer());
            } else {
                showUnifiedBackupList(sender, resolved);
            }
            return;
        }

        // Parse CLI arguments and flags
        boolean isEnder = false;
        boolean isForce = false;
        String action = null;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.equalsIgnoreCase("--ender")) {
                isEnder = true;
            } else if (arg.equalsIgnoreCase("--force")) {
                isForce = true;
            } else if (action == null) {
                action = arg;
            }
        }

        if (action == null || action.equalsIgnoreCase("list")) {
            showUnifiedBackupList(sender, resolved);
            return;
        }

        // A partir de aqui la orden MUTA el inventario del jugador: viewbackups ya no basta (#356).
        if (!denyIfCannotMutate(sender)) return;

        // --force omite la barrera entre modalidades: exige el permiso de cruce explicito (#356).
        if (isForce && !mayForce(sender instanceof Player, sender.hasPermission(WorldGroupPolicy.BYPASS_PERMISSION))) {
            sender.sendMessage("§c[IRP] §f--force §csalta el aislamiento entre modalidades y requiere §f"
                    + WorldGroupPolicy.BYPASS_PERMISSION + "§c.");
            return;
        }

        // Select backup entry
        Optional<BackupEntry> optBackup = selectBackupEntry(resolved, action);
        if (!optBackup.isPresent()) {
            sender.sendMessage("§c[IRP] No se encontró respaldo para §e" + resolved.getName() + " §ccoincidente con: §f" + action);
            sender.sendMessage("§7Usa §f/irp restore " + resolved.getName() + " list §7para consultar los respaldos disponibles.");
            return;
        }

        BackupEntry backup = optBackup.get();

        // Perform restore (live if online, queued if offline)
        Player targetPlayer = Bukkit.getPlayer(resolved.getUuid());
        if (targetPlayer != null && targetPlayer.isOnline()) {
            // Live player restore
            if (!isForce && !WorldGroupPolicy.mayRestore(sender, targetPlayer, backup.getWorld())) {
                return;
            }
            executeLiveRestore(sender, targetPlayer, backup, isEnder, isForce);
        } else {
            // Offline player queue
            executeOfflineQueue(sender, resolved, backup, isEnder, isForce);
        }
    }

    private Optional<BackupEntry> selectBackupEntry(PlayerResolver.ResolvedPlayer resolved, String action) {
        if (action.equalsIgnoreCase("latest")) {
            return BackupQueryUtil.getLatestBackup(resolved.getUuid());
        }

        // Check if numeric index #
        if (action.startsWith("#")) {
            action = action.substring(1);
        }

        // Try parsing pure integer
        try {
            int index = Integer.parseInt(action);
            // If small positive number, treat as backup index #
            if (index >= 1 && index <= 1000) {
                return BackupQueryUtil.getBackupByIndex(resolved.getUuid(), index);
            }
            // Otherwise treat as timestamp
            return BackupQueryUtil.getBackupByRelativeTime(resolved.getUuid(), index * 1000L);
        } catch (NumberFormatException ignored) {}

        // Relative time string: e.g. 10m, 30m, 2h, 1d
        long delta = parseTimeDelta(action);
        if (delta > 0) {
            long targetTimestamp = System.currentTimeMillis() - delta;
            return BackupQueryUtil.getBackupByRelativeTime(resolved.getUuid(), targetTimestamp);
        }

        return Optional.empty();
    }

    private void executeLiveRestore(CommandSender sender, Player targetPlayer, BackupEntry backup, boolean isEnder, boolean isForce) {
        PlayerData data = new PlayerData(targetPlayer.getUniqueId(), backup.getLogType(), backup.getTimestamp());

        // Snapshot PRE_RESTORE undo point
        new SaveInventory(targetPlayer, LogType.FORCE, null, "PRE_RESTORE")
                .snapshotAndSave(targetPlayer.getInventory(), targetPlayer.getEnderChest(), false);

        if (isEnder) {
            ItemStack[] ec = data.getEnderChest();
            if (ec == null) ec = new ItemStack[0];
            targetPlayer.getEnderChest().setContents(ec);

            sender.sendMessage("§a[IRP] Ender Chest restaurado con éxito para §e" + targetPlayer.getName()
                    + " §adesde respaldo §f" + backup.getFormattedTime() + " §7(" + backup.getLogType() + ")");
            targetPlayer.sendMessage("§8[§d§lSAORI§8] §a¡Tu Ender Chest ha sido restaurado exitosamente!");
        } else {
            ItemStack[] mainInv = data.getMainInventory();
            if (mainInv != null && mainInv.length > 0) {
                targetPlayer.getInventory().setContents(mainInv);
            }
            ItemStack[] armour = data.getArmour();
            if (armour != null && armour.length > 0) {
                try {
                    targetPlayer.getInventory().setArmorContents(armour);
                } catch (Throwable ignored) {}
            }
            RestoreInventory.setTotalExperience(targetPlayer, data.getXP());

            sender.sendMessage("§a[IRP] Inventario restaurado con éxito para §e" + targetPlayer.getName()
                    + " §adesde respaldo §f" + backup.getFormattedTime() + " §7(" + backup.getLogType() + ")");
            targetPlayer.sendMessage("§8[§d§lSAORI§8] §a¡Tu inventario ha sido restaurado exitosamente!");
        }

        try {
            if (SoundData.isInventoryRestoreEnabled()) {
                targetPlayer.playSound(targetPlayer.getLocation(), SoundData.getInventoryRestored(), 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {}
    }

    private void executeOfflineQueue(CommandSender sender, PlayerResolver.ResolvedPlayer resolved, BackupEntry backup, boolean isEnder, boolean isForce) {
        PendingRestoreManager pendingMgr = InventoryRollbackPlus.getInstance().getPendingRestoreManager();
        if (pendingMgr == null) {
            sender.sendMessage("§c[IRP] Gestor de restauraciones pendientes no disponible.");
            return;
        }

        PendingRestoreManager.PendingRestore pending = new PendingRestoreManager.PendingRestore(
                resolved.getUuid(),
                resolved.getName(),
                backup.getLogType(),
                backup.getTimestamp(),
                backup.getWorld(),
                backup.getModalityGroup(),
                isEnder,
                isForce,
                sender.getName(),
                System.currentTimeMillis()
        );

        pendingMgr.queueRestore(pending);

        sender.sendMessage("§a[IRP] El jugador §e" + resolved.getName() + " §aestá OFFLINE.");
        sender.sendMessage("§aOrden registrada en §fpending_restores.yml§a.");
        sender.sendMessage("§7Se aplicará automáticamente con notificación sonora cuando §e" + resolved.getName()
                + " §7conecte e ingrese a la modalidad §b" + backup.getModalityGroup()
                + (isForce ? " §c(con --force inmediato)" : "") + "§7.");
    }

    private void showUnifiedBackupList(CommandSender sender, PlayerResolver.ResolvedPlayer resolved) {
        List<BackupEntry> backups = BackupQueryUtil.getUnifiedBackups(resolved.getUuid());
        if (backups.isEmpty()) {
            sender.sendMessage("§e[IRP] No hay respaldos registrados para §6" + resolved.getName() + " §7(" + resolved.getUuid() + ").");
            return;
        }

        sender.sendMessage("§8════════════════════════════════════════════════════════════");
        sender.sendMessage("§8[§d§lIRP§8] §6Respaldos unificados de §e" + resolved.getName() + " §7(" + resolved.getUuid() + "):");
        int max = Math.min(backups.size(), 10);
        for (int i = 0; i < max; i++) {
            BackupEntry b = backups.get(i);
            String reason = b.getDeathReason().isEmpty() ? "" : " §8[" + b.getDeathReason() + "]";
            sender.sendMessage("§e#" + (i + 1) + " §7| §f" + b.getFormattedTime()
                    + " §7| §b" + b.getLogType()
                    + " §7| §a" + b.getWorld() + " (" + b.getModalityGroup() + ")"
                    + reason);
        }
        if (backups.size() > 10) {
            sender.sendMessage("§7... y §f" + (backups.size() - 10) + " §7respaldos más.");
        }
        sender.sendMessage("§7Para restaurar: §f/irp restore " + resolved.getName() + " <#|latest|Xm> [--ender] [--force]");
        sender.sendMessage("§8════════════════════════════════════════════════════════════");
    }

    private void listPendingRestores(CommandSender sender) {
        PendingRestoreManager pendingMgr = InventoryRollbackPlus.getInstance().getPendingRestoreManager();
        if (pendingMgr == null) {
            sender.sendMessage("§c[IRP] Gestor de restauraciones pendientes no disponible.");
            return;
        }
        List<PendingRestoreManager.PendingRestore> list = pendingMgr.getAllPending();
        if (list.isEmpty()) {
            sender.sendMessage("§a[IRP] No hay restauraciones offline pendientes en la cola.");
            return;
        }
        sender.sendMessage("§8[§d§lIRP§8] §6Restauraciones offline pendientes (" + list.size() + "):");
        for (PendingRestoreManager.PendingRestore p : list) {
            sender.sendMessage("§e- §f" + p.getPlayerName() + " §7(" + p.getUuid() + ")"
                    + " §7-> §b" + p.getTargetGroup() + " §7(" + p.getLogType() + " @ " + p.getFormattedBackupTime() + ")"
                    + (p.isRestoreEnder() ? " §d[ENDER]" : " §a[INV]")
                    + (p.isForce() ? " §c[FORCE]" : "")
                    + " §7por §f" + p.getQueuedBy());
        }
        sender.sendMessage("§7Para cancelar: §f/irp restore cancel-pending <jugador>");
    }

    private void cancelPendingRestore(CommandSender sender, String playerNameOrUuid) {
        if (!denyIfCannotMutate(sender)) return;

        PendingRestoreManager pendingMgr = InventoryRollbackPlus.getInstance().getPendingRestoreManager();
        if (pendingMgr == null) return;

        Optional<PlayerResolver.ResolvedPlayer> opt = PlayerResolver.resolvePlayer(playerNameOrUuid);
        UUID targetUuid = opt.map(PlayerResolver.ResolvedPlayer::getUuid).orElse(null);
        if (targetUuid == null) {
            targetUuid = PlayerResolver.tryParseUuid(playerNameOrUuid);
        }

        if (targetUuid == null) {
            sender.sendMessage("§c[IRP] No se pudo determinar el UUID del jugador para cancelar.");
            return;
        }

        if (pendingMgr.cancelPending(targetUuid)) {
            sender.sendMessage("§a[IRP] Restauración offline cancelada para §e" + playerNameOrUuid);
        } else {
            sender.sendMessage("§e[IRP] No existía ninguna restauración pendiente para §f" + playerNameOrUuid);
        }
    }

    /** Devuelve true si el emisor puede mutar; si no, ya envio el mensaje de denegacion. */
    private boolean denyIfCannotMutate(CommandSender sender) {
        if (mayMutate(sender instanceof Player, sender.hasPermission(PERM_RESTORE))) return true;
        sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNoPermission());
        sender.sendMessage("§7El permiso §f" + PERM_VIEW + " §7solo permite consultar respaldos; restaurar exige §f"
                + PERM_RESTORE + "§7.");
        return false;
    }

    private void sendCliHelp(CommandSender sender) {
        sender.sendMessage("§8[§d§lIRP§8] §6Comando de Restauración Unificado:");
        sender.sendMessage("§f/irp restore <jugador> [list|latest|#|Xm] [--ender] [--force]");
        sender.sendMessage("§7Opciones:");
        sender.sendMessage("§e  list         §7- Muestra lista numerada de respaldos unificados");
        sender.sendMessage("§e  latest       §7- Restaura el respaldo más reciente");
        sender.sendMessage("§e  # (ej. 1, 2) §7- Restaura el respaldo con ese número de índice");
        sender.sendMessage("§e  Xm / Xh      §7- Restaura el respaldo más cercano a X tiempo atrás");
        sender.sendMessage("§e  --ender      §7- Restaura el cofre de ender en lugar del inventario");
        sender.sendMessage("§e  --force      §7- Omite la restricción de modalidad");
        sender.sendMessage("§e  pending      §7- Lista órdenes offline en pending_restores.yml");
    }

    private void openMainMenu(Player staff) {
        MainMenu menu = new MainMenu(staff, 1);
        staff.openInventory(menu.getInventory());
        Bukkit.getScheduler().runTaskAsynchronously(InventoryRollback.getInstance(), menu::getMainMenu);
    }

    private void openPlayerMenu(Player staff, OfflinePlayer offlinePlayer) {
        ModalityMenu menu = new ModalityMenu(staff, offlinePlayer);
        staff.openInventory(menu.getInventory());
    }

    public static long parseTimeDelta(String str) {
        if (str == null || str.isEmpty()) return 0;
        str = str.trim().toLowerCase();
        try {
            if (str.endsWith("m")) {
                long min = Long.parseLong(str.substring(0, str.length() - 1));
                return min * 60 * 1000L;
            } else if (str.endsWith("h")) {
                long hours = Long.parseLong(str.substring(0, str.length() - 1));
                return hours * 3600 * 1000L;
            } else if (str.endsWith("d")) {
                long days = Long.parseLong(str.substring(0, str.length() - 1));
                return days * 86400 * 1000L;
            } else {
                long min = Long.parseLong(str);
                return min * 60 * 1000L;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
