package com.nuclyon.technicallycoded.inventoryrollback.restore;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.config.SoundData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.PlayerData;
import me.danjono.inventoryrollback.inventory.RestoreInventory;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PendingRestoreManager {

    private static PendingRestoreManager instance;

    private final File storageFile;
    private final Map<UUID, PendingRestore> pendingMap = new ConcurrentHashMap<>();

    public static class PendingRestore {
        private final UUID uuid;
        private final String playerName;
        private final LogType logType;
        private final long timestamp;
        private final String targetWorld;
        private final String targetGroup;
        private final boolean restoreEnder;
        private final boolean force;
        private final String queuedBy;
        private final long queuedAt;

        public PendingRestore(UUID uuid, String playerName, LogType logType, long timestamp,
                              String targetWorld, String targetGroup, boolean restoreEnder,
                              boolean force, String queuedBy, long queuedAt) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.logType = logType;
            this.timestamp = timestamp;
            this.targetWorld = targetWorld != null ? targetWorld : "unknown";
            this.targetGroup = targetGroup != null ? targetGroup : WorldGroupPolicy.groupOfWorld(this.targetWorld);
            this.restoreEnder = restoreEnder;
            this.force = force;
            this.queuedBy = queuedBy != null ? queuedBy : "CONSOLE";
            this.queuedAt = queuedAt > 0 ? queuedAt : System.currentTimeMillis();
        }

        public UUID getUuid() { return uuid; }
        public String getPlayerName() { return playerName; }
        public LogType getLogType() { return logType; }
        public long getTimestamp() { return timestamp; }
        public String getTargetWorld() { return targetWorld; }
        public String getTargetGroup() { return targetGroup; }
        public boolean isRestoreEnder() { return restoreEnder; }
        public boolean isForce() { return force; }
        public String getQueuedBy() { return queuedBy; }
        public long getQueuedAt() { return queuedAt; }

        public String getFormattedBackupTime() {
            try {
                return PlayerData.getTime(timestamp);
            } catch (Exception e) {
                return String.valueOf(timestamp);
            }
        }
    }

    public PendingRestoreManager(File storageFile) {
        this.storageFile = storageFile;
        instance = this;
        load();
    }

    public static PendingRestoreManager getInstance() {
        return instance;
    }

    public synchronized void load() {
        pendingMap.clear();
        if (storageFile == null || !storageFile.exists()) {
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
            ConfigurationSection sec = yaml.getConfigurationSection("pending");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        UUID u = UUID.fromString(key);
                        String name = sec.getString(key + ".name", key);
                        String logTypeStr = sec.getString(key + ".logType", "DEATH");
                        LogType logType;
                        try {
                            logType = LogType.valueOf(logTypeStr);
                        } catch (Exception e) {
                            logType = LogType.FORCE;
                        }
                        long timestamp = sec.getLong(key + ".timestamp", 0);
                        String world = sec.getString(key + ".targetWorld", "unknown");
                        String group = sec.getString(key + ".targetGroup", WorldGroupPolicy.groupOfWorld(world));
                        boolean ender = sec.getBoolean(key + ".restoreEnder", false);
                        boolean force = sec.getBoolean(key + ".force", false);
                        String queuedBy = sec.getString(key + ".queuedBy", "CONSOLE");
                        long queuedAt = sec.getLong(key + ".queuedAt", System.currentTimeMillis());

                        if (timestamp > 0) {
                            pendingMap.put(u, new PendingRestore(u, name, logType, timestamp, world, group, ender, force, queuedBy, queuedAt));
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            if (InventoryRollbackPlus.getInstance() != null) {
                InventoryRollbackPlus.getInstance().getLogger().log(Level.WARNING, "Error loading pending_restores.yml", e);
            }
        }
    }

    public synchronized void save() {
        if (storageFile == null) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, PendingRestore> entry : pendingMap.entrySet()) {
                String path = "pending." + entry.getKey().toString();
                PendingRestore r = entry.getValue();
                yaml.set(path + ".name", r.getPlayerName());
                yaml.set(path + ".logType", r.getLogType().name());
                yaml.set(path + ".timestamp", r.getTimestamp());
                yaml.set(path + ".targetWorld", r.getTargetWorld());
                yaml.set(path + ".targetGroup", r.getTargetGroup());
                yaml.set(path + ".restoreEnder", r.isRestoreEnder());
                yaml.set(path + ".force", r.isForce());
                yaml.set(path + ".queuedBy", r.getQueuedBy());
                yaml.set(path + ".queuedAt", r.getQueuedAt());
            }

            if (storageFile.getParentFile() != null && !storageFile.getParentFile().exists()) {
                storageFile.getParentFile().mkdirs();
            }
            yaml.save(storageFile);
        } catch (Exception e) {
            e.printStackTrace();
            if (InventoryRollbackPlus.getInstance() != null) {
                InventoryRollbackPlus.getInstance().getLogger().log(Level.SEVERE, "Could not save pending_restores.yml", e);
            }
        }
    }

    public void queueRestore(PendingRestore restore) {
        if (restore == null || restore.getUuid() == null) return;
        pendingMap.put(restore.getUuid(), restore);
        save();
        if (InventoryRollbackPlus.getInstance() != null) {
            InventoryRollbackPlus.getInstance().getLogger().info(
                    "[IRP] Queued offline restore for " + restore.getPlayerName() + " (" + restore.getUuid() + ") to " + restore.getTargetGroup() +
                            " [Ender=" + restore.isRestoreEnder() + ", Force=" + restore.isForce() + "]"
            );
        }
    }

    public Optional<PendingRestore> getPending(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(pendingMap.get(uuid));
    }

    public boolean cancelPending(UUID uuid) {
        if (uuid == null) return false;
        boolean removed = pendingMap.remove(uuid) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public List<PendingRestore> getAllPending() {
        return new ArrayList<>(pendingMap.values());
    }

    /**
     * Decide si la copia puede aplicarse en el grupo donde esta el jugador ahora mismo.
     * Extraido como funcion pura para poder probar el contrato de modalidad sin un servidor.
     */
    public static boolean canApplyInGroup(String currentGroup, String targetGroup, boolean force) {
        if (force) return true;
        if (targetGroup == null || "unknown".equalsIgnoreCase(targetGroup)) return true;
        return targetGroup.equalsIgnoreCase(currentGroup);
    }

    /**
     * Programa la comprobacion para el tick SIGUIENTE al evento (#349).
     * BentoBox/InvSwitcher inyecta el inventario de la modalidad dentro del propio
     * PlayerChangedWorldEvent; aplicar en el mismo tick hace que su escritura pise
     * la restauracion y el jugador se quede sin sus items.
     */
    public void scheduleCheckAndApply(final Player player) {
        if (player == null) return;

        InventoryRollbackPlus plugin = InventoryRollbackPlus.getInstance();
        if (plugin == null) {
            checkAndApply(player);
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    // El jugador pudo desconectar o volver a cambiar de mundo en ese tick.
                    if (!player.isOnline()) return;
                    checkAndApply(player);
                }
            });
        } catch (Throwable error) {
            // Sin scheduler disponible (apagado o entorno de pruebas) se aplica en linea.
            checkAndApply(player);
        }
    }

    /**
     * Checks if player has a pending restore and applies it if they are in the target modality or force is true.
     * Must be called on Bukkit thread when player joins or changes worlds.
     * Prefiere {@link #scheduleCheckAndApply(Player)} desde un listener de mundo.
     */
    public boolean checkAndApply(Player player) {
        if (player == null) return false;
        PendingRestore pending = pendingMap.get(player.getUniqueId());
        if (pending == null) return false;

        String currentWorld = player.getWorld().getName();
        String currentGroup = WorldGroupPolicy.groupOfWorld(currentWorld);

        boolean canApply = canApplyInGroup(currentGroup, pending.getTargetGroup(), pending.isForce());

        if (!canApply) {
            player.sendMessage("§8[§d§lSAORI§8] §eTienes una restauración de inventario pendiente para la modalidad §b"
                    + pending.getTargetGroup() + "§e. Se aplicará automáticamente cuando ingreses a ella.");
            return false;
        }

        // Apply restore
        try {
            // 1. Snapshot PRE_RESTORE undo point
            new SaveInventory(player, LogType.FORCE, null, "PRE_RESTORE")
                    .snapshotAndSave(player.getInventory(), player.getEnderChest(), false);

            // 2. Load backup data
            PlayerData data = new PlayerData(player.getUniqueId(), pending.getLogType(), pending.getTimestamp());

            if (pending.isRestoreEnder()) {
                ItemStack[] ec = data.getEnderChest();
                if (ec == null) ec = new ItemStack[0];
                player.getEnderChest().setContents(ec);
            } else {
                ItemStack[] inv = data.getMainInventory();
                if (inv != null && inv.length > 0) {
                    player.getInventory().setContents(inv);
                }
                ItemStack[] armour = data.getArmour();
                if (armour != null && armour.length > 0) {
                    try {
                        player.getInventory().setArmorContents(armour);
                    } catch (Throwable ignored) {}
                }
                RestoreInventory.setTotalExperience(player, data.getXP());
            }

            // 3. Play sound
            try {
                if (SoundData.isInventoryRestoreEnabled()) {
                    player.playSound(player.getLocation(), SoundData.getInventoryRestored(), 1.0f, 1.0f);
                }
            } catch (Throwable ignored) {}

            // 4. Welcome & confirmation message
            player.sendMessage("§8[§d§lSAORI§8] §a¡Tu inventario ha sido restaurado exitosamente tras tu solicitud!");
            player.sendMessage("§7Restauración aplicada desde copia de §f" + pending.getFormattedBackupTime()
                    + "§7 (" + pending.getLogType() + ") en §e" + currentGroup + "§7.");

            // 5. Remove from queue and save
            pendingMap.remove(player.getUniqueId());
            save();

            if (InventoryRollbackPlus.getInstance() != null) {
                InventoryRollbackPlus.getInstance().getLogger().info(
                        "[IRP] Applied pending offline restore for " + player.getName() + " (" + player.getUniqueId() +
                                ") from backup " + pending.getTimestamp() + " in world " + currentWorld + " (" + currentGroup + ")"
                );
            }
            return true;
        } catch (Exception e) {
            if (InventoryRollbackPlus.getInstance() != null) {
                InventoryRollbackPlus.getInstance().getLogger().log(Level.SEVERE, "Failed to apply pending restore for " + player.getName(), e);
            }
            return false;
        }
    }
}
