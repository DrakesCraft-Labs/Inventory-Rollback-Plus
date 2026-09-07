package com.nuclyon.technicallycoded.inventoryrollback.restore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.data.YAML;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

/**
 * Secure PlayerResolver for DrakesCraft.
 * Enforces: NEVER generate or accept synthetic offline UUIDs out of nowhere.
 * Strictly validates player names and UUIDs against pre-registered server databases:
 * 1) Paper usercache.json
 * 2) BentoBox/database/Names/
 * 3) Existing folders in plugins/InventoryRollbackPlus/backups/
 * 4) Active online players
 * 5) Bukkit registered offline players
 *
 * Automatically disambiguates multiple candidate profiles (e.g. Pasiente premium vs no-premium/bedrock)
 * by selecting the registered UUID with legitimate backup history.
 */
public class PlayerResolver {

    public static class ResolvedPlayer {
        private final UUID uuid;
        private final String name;
        private final OfflinePlayer offlinePlayer;
        private final int backupCount;
        private final long latestBackupTimestamp;
        private final String source;

        public ResolvedPlayer(UUID uuid, String name, OfflinePlayer offlinePlayer, int backupCount, long latestBackupTimestamp, String source) {
            this.uuid = uuid;
            this.name = name;
            this.offlinePlayer = offlinePlayer;
            this.backupCount = backupCount;
            this.latestBackupTimestamp = latestBackupTimestamp;
            this.source = source;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            if (name != null && !name.trim().isEmpty()) return name;
            if (offlinePlayer != null && offlinePlayer.getName() != null) return offlinePlayer.getName();
            return uuid.toString();
        }

        public OfflinePlayer getOfflinePlayer() {
            return offlinePlayer != null ? offlinePlayer : Bukkit.getOfflinePlayer(uuid);
        }

        public int getBackupCount() {
            return backupCount;
        }

        public long getLatestBackupTimestamp() {
            return latestBackupTimestamp;
        }

        public String getSource() {
            return source;
        }

        public boolean isOnline() {
            return Bukkit.getPlayer(uuid) != null;
        }

        @Override
        public String toString() {
            return "ResolvedPlayer{" +
                    "uuid=" + uuid +
                    ", name='" + getName() + '\'' +
                    ", backupCount=" + backupCount +
                    ", latestBackupTimestamp=" + latestBackupTimestamp +
                    ", source='" + source + '\'' +
                    '}';
        }
    }

    public static class Candidate {
        public final UUID uuid;
        public String name;
        public String source;
        public int backupCount = 0;
        public long latestBackupTimestamp = 0;
        public boolean isOnline = false;
        public boolean hasPlayed = false;

        public Candidate(UUID uuid, String name, String source) {
            this.uuid = uuid;
            this.name = name;
            this.source = source;
        }
    }

    public static Optional<ResolvedPlayer> resolvePlayer(String input) {
        return resolvePlayer(input, null, null, null);
    }

    /**
     * Resolves player with optional overrides for testing.
     */
    public static Optional<ResolvedPlayer> resolvePlayer(String input, File customUsercache, File customBentoBoxDir, File customBackupsRoot) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }
        String cleanInput = input.trim();

        // 1. Check if input is directly a UUID string (36 chars or 32 chars)
        UUID directUuid = tryParseUuid(cleanInput);
        if (directUuid != null) {
            return resolveByExactUuid(directUuid, cleanInput, customUsercache, customBentoBoxDir, customBackupsRoot);
        }

        // 2. Collect candidate UUIDs strictly from registered databases
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();

        // 2.1 Online players
        collectOnlineCandidates(cleanInput, candidates);

        // 2.2 Paper usercache.json
        collectUsercacheCandidates(cleanInput, candidates, customUsercache);

        // 2.3 BentoBox database/Names
        collectBentoBoxCandidates(cleanInput, candidates, customBentoBoxDir);

        // 2.4 IRP backups on disk
        collectIrpBackupCandidates(cleanInput, candidates, customBackupsRoot);

        // 2.5 Bukkit known offline players
        collectBukkitOfflineCandidates(cleanInput, candidates);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // 3. Populate statistics for disambiguation
        for (Candidate c : candidates.values()) {
            populateCandidateStats(c, customBackupsRoot);
        }

        // 4. Disambiguation
        List<Candidate> sorted = new ArrayList<>(candidates.values());
        sorted.sort((a, b) -> {
            // Priority 1: Has legitimate backups in IRP
            int aHasBackups = a.backupCount > 0 ? 1 : 0;
            int bHasBackups = b.backupCount > 0 ? 1 : 0;
            if (aHasBackups != bHasBackups) {
                return Integer.compare(bHasBackups, aHasBackups);
            }

            // Priority 2: Most recent backup timestamp
            if (a.latestBackupTimestamp != b.latestBackupTimestamp) {
                return Long.compare(b.latestBackupTimestamp, a.latestBackupTimestamp);
            }

            // Priority 3: Total backup count
            if (a.backupCount != b.backupCount) {
                return Integer.compare(b.backupCount, a.backupCount);
            }

            // Priority 4: Online status
            if (a.isOnline != b.isOnline) {
                return Boolean.compare(b.isOnline, a.isOnline);
            }

            // Priority 5: Has played before
            if (a.hasPlayed != b.hasPlayed) {
                return Boolean.compare(b.hasPlayed, a.hasPlayed);
            }

            return 0;
        });

        Candidate best = sorted.get(0);
        OfflinePlayer op = null;
        try {
            op = Bukkit.getOfflinePlayer(best.uuid);
        } catch (Throwable ignored) {}

        String resolvedName = best.name;
        if (resolvedName == null || resolvedName.trim().isEmpty()) {
            if (op != null && op.getName() != null) resolvedName = op.getName();
            else resolvedName = cleanInput;
        }

        if (sorted.size() > 1 && InventoryRollbackPlus.getInstance() != null) {
            InventoryRollbackPlus.getInstance().getLogger().info(
                    "[PlayerResolver] Disambiguated '" + cleanInput + "': Selected UUID " + best.uuid +
                            " (" + best.backupCount + " backups, latest: " + best.latestBackupTimestamp + ", source=" + best.source + ") out of " + sorted.size() + " registered candidates."
            );
        }

        return Optional.of(new ResolvedPlayer(best.uuid, resolvedName, op, best.backupCount, best.latestBackupTimestamp, best.source));
    }

    private static Optional<ResolvedPlayer> resolveByExactUuid(UUID uuid, String rawInput, File customUsercache, File customBentoBoxDir, File customBackupsRoot) {
        Candidate c = new Candidate(uuid, null, "direct_uuid");
        populateCandidateStats(c, customBackupsRoot);

        // Check if this UUID is known in usercache
        findNameInUsercache(uuid, c, customUsercache);

        // Check if offline player exists
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null) {
                if (c.name == null && op.getName() != null) c.name = op.getName();
                if (op.hasPlayedBefore()) c.hasPlayed = true;
            }
        } catch (Throwable ignored) {}

        // Validation: must have backups, or be online, or have played before, or be registered in usercache/bentobox
        boolean isRegistered = c.backupCount > 0 || c.isOnline || c.hasPlayed || c.name != null;
        if (!isRegistered) {
            return Optional.empty();
        }

        OfflinePlayer op = null;
        try {
            op = Bukkit.getOfflinePlayer(uuid);
        } catch (Throwable ignored) {}

        String finalName = c.name != null ? c.name : (op != null && op.getName() != null ? op.getName() : rawInput);
        return Optional.of(new ResolvedPlayer(uuid, finalName, op, c.backupCount, c.latestBackupTimestamp, c.source));
    }

    private static void populateCandidateStats(Candidate c, File customBackupsRoot) {
        try {
            c.isOnline = Bukkit.getPlayer(c.uuid) != null;
        } catch (Throwable ignored) {}

        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(c.uuid);
            c.hasPlayed = op != null && op.hasPlayedBefore();
            if (c.name == null && op != null && op.getName() != null) {
                c.name = op.getName();
            }
        } catch (Throwable ignored) {}

        if (customBackupsRoot != null) {
            // Count backups in custom root
            int count = 0;
            long latest = 0;
            File[] types = customBackupsRoot.listFiles(File::isDirectory);
            if (types != null) {
                for (File typeDir : types) {
                    File pFolder = new File(typeDir, c.uuid.toString());
                    if (pFolder.exists() && pFolder.isDirectory()) {
                        File[] ymls = pFolder.listFiles((dir, name) -> name.endsWith(".yml"));
                        if (ymls != null) {
                            count += ymls.length;
                            for (File yf : ymls) {
                                try {
                                    long ts = Long.parseLong(yf.getName().replace(".yml", ""));
                                    if (ts > latest) latest = ts;
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
            c.backupCount = count;
            c.latestBackupTimestamp = latest;
        } else {
            c.backupCount = BackupQueryUtil.countTotalBackups(c.uuid);
            c.latestBackupTimestamp = BackupQueryUtil.getLatestTimestamp(c.uuid);
        }
    }

    private static void collectOnlineCandidates(String inputName, Map<UUID, Candidate> candidates) {
        try {
            Player p = Bukkit.getPlayerExact(inputName);
            if (p != null) {
                candidates.put(p.getUniqueId(), new Candidate(p.getUniqueId(), p.getName(), "online_exact"));
            }
            for (Player op : Bukkit.getOnlinePlayers()) {
                if (matchesPlayerName(op.getName(), inputName)) {
                    candidates.putIfAbsent(op.getUniqueId(), new Candidate(op.getUniqueId(), op.getName(), "online_list"));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void collectUsercacheCandidates(String inputName, Map<UUID, Candidate> candidates, File customUsercache) {
        File usercacheFile = customUsercache;
        if (usercacheFile == null) {
            usercacheFile = new File("usercache.json");
            if (!usercacheFile.exists()) {
                try {
                    File container = Bukkit.getWorldContainer();
                    if (container != null) {
                        File alt = new File(container, "usercache.json");
                        if (alt.exists()) usercacheFile = alt;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (usercacheFile == null || !usercacheFile.exists() || !usercacheFile.isFile()) {
            return;
        }

        try (FileReader reader = new FileReader(usercacheFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root != null && root.isJsonArray()) {
                JsonArray arr = root.getAsJsonArray();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("name") && obj.has("uuid")) {
                        String entryName = obj.get("name").getAsString();
                        if (matchesPlayerName(entryName, inputName)) {
                            try {
                                UUID u = UUID.fromString(obj.get("uuid").getAsString());
                                candidates.putIfAbsent(u, new Candidate(u, entryName, "usercache"));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (InventoryRollbackPlus.getInstance() != null) {
                InventoryRollbackPlus.getInstance().getLogger().log(Level.FINE, "Error reading usercache.json in PlayerResolver", e);
            }
        }
    }

    private static void findNameInUsercache(UUID targetUuid, Candidate candidate, File customUsercache) {
        File usercacheFile = customUsercache;
        if (usercacheFile == null) {
            usercacheFile = new File("usercache.json");
            if (!usercacheFile.exists()) {
                try {
                    File container = Bukkit.getWorldContainer();
                    if (container != null) {
                        File alt = new File(container, "usercache.json");
                        if (alt.exists()) usercacheFile = alt;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (usercacheFile == null || !usercacheFile.exists() || !usercacheFile.isFile()) return;

        try (FileReader reader = new FileReader(usercacheFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root != null && root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("name") && obj.has("uuid")) {
                        String uuidStr = obj.get("uuid").getAsString();
                        if (targetUuid.toString().equalsIgnoreCase(uuidStr)) {
                            candidate.name = obj.get("name").getAsString();
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void collectBentoBoxCandidates(String inputName, Map<UUID, Candidate> candidates, File customBentoBoxDir) {
        File namesDir = customBentoBoxDir;
        if (namesDir == null) {
            try {
                if (InventoryRollbackPlus.getInstance() != null && InventoryRollbackPlus.getInstance().getDataFolder() != null) {
                    File pluginsDir = InventoryRollbackPlus.getInstance().getDataFolder().getParentFile();
                    if (pluginsDir != null) {
                        namesDir = new File(pluginsDir, "BentoBox/database/Names");
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (namesDir == null || !namesDir.exists() || !namesDir.isDirectory()) {
            return;
        }

        File[] jsonFiles = namesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (jsonFiles == null) return;

        for (File f : jsonFiles) {
            try (FileReader reader = new FileReader(f, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root != null && root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    String name = obj.has("name") ? obj.get("name").getAsString() : null;
                    String uuidStr = obj.has("uniqueId") ? obj.get("uniqueId").getAsString() : null;
                    if (uuidStr == null && f.getName().length() >= 36) {
                        uuidStr = f.getName().substring(0, 36);
                    }
                    if (name != null && uuidStr != null && matchesPlayerName(name, inputName)) {
                        try {
                            UUID u = UUID.fromString(uuidStr);
                            candidates.putIfAbsent(u, new Candidate(u, name, "bentobox"));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void collectIrpBackupCandidates(String inputName, Map<UUID, Candidate> candidates, File customBackupsRoot) {
        File backupsRoot = customBackupsRoot;
        if (backupsRoot == null) {
            try {
                backupsRoot = YAML.getRootBackupsFolder();
            } catch (Throwable ignored) {}
        }
        if (backupsRoot == null || !backupsRoot.exists() || !backupsRoot.isDirectory()) {
            return;
        }

        File[] typeDirs = backupsRoot.listFiles(File::isDirectory);
        if (typeDirs == null) return;

        Set<String> checkedUuids = new HashSet<>();

        for (File typeDir : typeDirs) {
            File[] uuidDirs = typeDir.listFiles(File::isDirectory);
            if (uuidDirs == null) continue;

            for (File uFolder : uuidDirs) {
                String folderName = uFolder.getName();
                if (checkedUuids.add(folderName)) {
                    UUID u = tryParseUuid(folderName);
                    if (u != null) {
                        try {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                            if (op != null && op.getName() != null && matchesPlayerName(op.getName(), inputName)) {
                                candidates.putIfAbsent(u, new Candidate(u, op.getName(), "irp_backups"));
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private static void collectBukkitOfflineCandidates(String inputName, Map<UUID, Candidate> candidates) {
        try {
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && matchesPlayerName(op.getName(), inputName)) {
                    if (op.hasPlayedBefore()) {
                        candidates.putIfAbsent(op.getUniqueId(), new Candidate(op.getUniqueId(), op.getName(), "bukkit_offline"));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean matchesPlayerName(String registeredName, String queryName) {
        if (registeredName == null || queryName == null) return false;
        if (registeredName.equalsIgnoreCase(queryName)) return true;

        // Bedrock Floodgate prefix handling (e.g. .Pasiente vs Pasiente)
        if (registeredName.startsWith(".") && registeredName.substring(1).equalsIgnoreCase(queryName)) {
            return true;
        }
        if (queryName.startsWith(".") && queryName.substring(1).equalsIgnoreCase(registeredName)) {
            return true;
        }

        return false;
    }

    public static UUID tryParseUuid(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 36) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {}
        } else if (s.length() == 32) {
            try {
                String formatted = s.substring(0, 8) + "-" +
                        s.substring(8, 12) + "-" +
                        s.substring(12, 16) + "-" +
                        s.substring(16, 20) + "-" +
                        s.substring(20);
                return UUID.fromString(formatted);
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
}
