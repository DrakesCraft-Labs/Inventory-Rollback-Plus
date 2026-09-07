package com.nuclyon.technicallycoded.inventoryrollback.restore;

import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.YAML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BackupQueryUtil {

    private static final LogType[] LOG_TYPES = new LogType[]{
            LogType.DEATH,
            LogType.QUIT,
            LogType.JOIN,
            LogType.WORLD_CHANGE,
            LogType.FORCE
    };

    public static List<BackupEntry> getUnifiedBackups(UUID uuid) {
        List<BackupEntry> entries = new ArrayList<>();
        if (uuid == null) return entries;

        for (LogType logType : LOG_TYPES) {
            File folder = YAML.getPlayerBackupLocation(logType, uuid);
            if (!folder.exists() || !folder.isDirectory()) continue;

            File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;

            for (File file : files) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String tsStr = name.substring(0, dot);

                long timestamp;
                try {
                    timestamp = Long.parseLong(tsStr);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Quick metadata scan
                String world = "unknown";
                String deathReason = "";

                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("world:")) {
                            world = trimmed.substring("world:".length()).trim();
                        } else if (trimmed.startsWith("deathReason:")) {
                            deathReason = trimmed.substring("deathReason:".length()).trim();
                        }
                    }
                } catch (Exception ignored) {}

                entries.add(new BackupEntry(uuid, logType, timestamp, world, deathReason, file));
            }
        }

        Collections.sort(entries);
        return entries;
    }

    public static Optional<BackupEntry> getLatestBackup(UUID uuid) {
        List<BackupEntry> list = getUnifiedBackups(uuid);
        if (list.isEmpty()) return Optional.empty();
        return Optional.of(list.get(0));
    }

    public static Optional<BackupEntry> getBackupByIndex(UUID uuid, int index1Based) {
        List<BackupEntry> list = getUnifiedBackups(uuid);
        if (index1Based < 1 || index1Based > list.size()) {
            return Optional.empty();
        }
        return Optional.of(list.get(index1Based - 1));
    }

    public static Optional<BackupEntry> getBackupByRelativeTime(UUID uuid, long targetTimestamp) {
        List<BackupEntry> list = getUnifiedBackups(uuid);
        if (list.isEmpty()) return Optional.empty();

        BackupEntry best = null;
        long minDiff = Long.MAX_VALUE;

        for (BackupEntry entry : list) {
            long diff = Math.abs(entry.getTimestamp() - targetTimestamp);
            if (diff < minDiff) {
                minDiff = diff;
                best = entry;
            }
        }

        return Optional.ofNullable(best);
    }

    public static int countTotalBackups(UUID uuid) {
        if (uuid == null) return 0;
        int count = 0;
        for (LogType logType : LOG_TYPES) {
            File folder = YAML.getPlayerBackupLocation(logType, uuid);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
                if (files != null) count += files.length;
            }
        }
        return count;
    }

    public static long getLatestTimestamp(UUID uuid) {
        if (uuid == null) return 0;
        long latest = 0;
        for (LogType logType : LOG_TYPES) {
            File folder = YAML.getPlayerBackupLocation(logType, uuid);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        int dot = name.lastIndexOf('.');
                        if (dot <= 0) continue;
                        try {
                            long ts = Long.parseLong(name.substring(0, dot));
                            if (ts > latest) latest = ts;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return latest;
    }
}
