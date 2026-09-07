package com.nuclyon.technicallycoded.inventoryrollback.restore;

import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.PlayerData;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;

import java.io.File;
import java.util.UUID;

public class BackupEntry implements Comparable<BackupEntry> {

    private final UUID uuid;
    private final LogType logType;
    private final long timestamp;
    private final String world;
    private final String modalityGroup;
    private final String deathReason;
    private final File file;

    public BackupEntry(UUID uuid, LogType logType, long timestamp, String world, String deathReason, File file) {
        this.uuid = uuid;
        this.logType = logType;
        this.timestamp = timestamp;
        this.world = world != null && !world.trim().isEmpty() ? world.trim() : "unknown";
        this.modalityGroup = WorldGroupPolicy.groupOfWorld(this.world);
        this.deathReason = deathReason != null ? deathReason.trim() : "";
        this.file = file;
    }

    public UUID getUuid() {
        return uuid;
    }

    public LogType getLogType() {
        return logType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getWorld() {
        return world;
    }

    public String getModalityGroup() {
        return modalityGroup;
    }

    public String getDeathReason() {
        return deathReason;
    }

    public File getFile() {
        return file;
    }

    public String getFormattedTime() {
        try {
            return PlayerData.getTime(timestamp);
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }

    @Override
    public int compareTo(BackupEntry o) {
        // Descending order: newest backups first
        return Long.compare(o.timestamp, this.timestamp);
    }

    @Override
    public String toString() {
        return "BackupEntry{" +
                "logType=" + logType +
                ", timestamp=" + timestamp +
                ", world='" + world + '\'' +
                ", modalityGroup='" + modalityGroup + '\'' +
                ", deathReason='" + deathReason + '\'' +
                '}';
    }
}
