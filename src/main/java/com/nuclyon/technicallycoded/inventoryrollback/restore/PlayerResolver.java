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

    /** Prefijo que Floodgate antepone al gamertag de una cuenta Bedrock (default de Geyser). */
    public static final String BEDROCK_PREFIX = ".";

    /** Plataforma de origen de un perfil registrado en el servidor. */
    public enum Platform {
        JAVA,
        BEDROCK,
        UNKNOWN
    }

    /** Con que fidelidad un nombre registrado responde a la consulta escrita por el operador. */
    public enum MatchQuality {
        /** Coincidencia literal ignorando mayusculas: misma cuenta, misma plataforma. */
        EXACT,
        /** Coincidencia tolerando el prefijo Floodgate: puede cruzar de plataforma. */
        PREFIX,
        /** No coincide. */
        NONE
    }

    /**
     * Un UUID sintetico de Floodgate ocupa solo los bits menos significativos:
     * 00000000-0000-0000-xxxx-xxxxxxxxxxxx. Ninguna cuenta Java premium tiene esa forma.
     */
    public static boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() != 0L;
    }

    /** Un nombre con prefijo Floodgate no puede pertenecer a una cuenta Java: Mojang no admite '.'. */
    public static boolean isBedrockName(String name) {
        return name != null && name.trim().startsWith(BEDROCK_PREFIX) && name.trim().length() > BEDROCK_PREFIX.length();
    }

    /** Clasifica un perfil por su UUID sintetico o por el prefijo de su nombre. */
    public static Platform platformOf(UUID uuid, String name) {
        if (isFloodgateUuid(uuid) || isBedrockName(name)) return Platform.BEDROCK;
        if (uuid != null || (name != null && !name.trim().isEmpty())) return Platform.JAVA;
        return Platform.UNKNOWN;
    }

    /** Plataforma que el operador esta pidiendo segun como escribio el nombre. */
    public static Platform platformOfQuery(String query) {
        if (query == null || query.trim().isEmpty()) return Platform.UNKNOWN;
        UUID asUuid = tryParseUuid(query);
        if (asUuid != null) return platformOf(asUuid, null);
        return isBedrockName(query) ? Platform.BEDROCK : Platform.JAVA;
    }

    public static class ResolvedPlayer {
        private final UUID uuid;
        private final String name;
        private final OfflinePlayer offlinePlayer;
        private final int backupCount;
        private final long latestBackupTimestamp;
        private final String source;
        private final Platform platform;
        private final MatchQuality quality;
        private final List<ResolvedPlayer> alternates;

        public ResolvedPlayer(UUID uuid, String name, OfflinePlayer offlinePlayer, int backupCount, long latestBackupTimestamp, String source) {
            this(uuid, name, offlinePlayer, backupCount, latestBackupTimestamp, source,
                    platformOf(uuid, name), MatchQuality.EXACT, Collections.emptyList());
        }

        public ResolvedPlayer(UUID uuid, String name, OfflinePlayer offlinePlayer, int backupCount, long latestBackupTimestamp,
                              String source, Platform platform, MatchQuality quality, List<ResolvedPlayer> alternates) {
            this.uuid = uuid;
            this.name = name;
            this.offlinePlayer = offlinePlayer;
            this.backupCount = backupCount;
            this.latestBackupTimestamp = latestBackupTimestamp;
            this.source = source;
            this.platform = platform != null ? platform : Platform.UNKNOWN;
            this.quality = quality != null ? quality : MatchQuality.EXACT;
            this.alternates = alternates != null ? Collections.unmodifiableList(new ArrayList<>(alternates)) : Collections.<ResolvedPlayer>emptyList();
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

        public Platform getPlatform() {
            return platform;
        }

        public boolean isBedrock() {
            return platform == Platform.BEDROCK;
        }

        public MatchQuality getQuality() {
            return quality;
        }

        /** Otros perfiles registrados que tambien respondian al nombre consultado. */
        public List<ResolvedPlayer> getAlternates() {
            return alternates;
        }

        /**
         * Hay homonimos de OTRA plataforma que coinciden con la misma fidelidad.
         * En ese caso el resolver no puede saber si el inventario es del Java o del Bedrock:
         * quien ordena debe desempatar con el UUID exacto (#351).
         */
        public boolean isCrossPlatformAmbiguous() {
            for (ResolvedPlayer other : alternates) {
                if (other.getQuality() == quality && other.getPlatform() != platform) return true;
            }
            return false;
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
                    ", platform=" + platform +
                    ", quality=" + quality +
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
        public Platform platform = Platform.UNKNOWN;
        public MatchQuality quality = MatchQuality.EXACT;

        public Candidate(UUID uuid, String name, String source) {
            this.uuid = uuid;
            this.name = name;
            this.source = source;
            this.platform = platformOf(uuid, name);
        }
    }

    /**
     * Registra un candidato conservando la mejor calidad de coincidencia.
     * El mismo UUID puede aparecer en varias fuentes (usercache, BentoBox, respaldos);
     * quedarse con el primer hallazgo perderia el dato de si el nombre coincidia literalmente.
     */
    private static void offer(Map<UUID, Candidate> candidates, UUID uuid, String name, String source, String query) {
        if (uuid == null) return;
        MatchQuality q = matchQuality(name, query);
        if (q == MatchQuality.NONE) return;

        Candidate existing = candidates.get(uuid);
        if (existing == null) {
            Candidate c = new Candidate(uuid, name, source);
            c.quality = q;
            candidates.put(uuid, c);
            return;
        }
        if (q.ordinal() < existing.quality.ordinal()) {
            existing.quality = q;
            existing.source = source;
        }
        if ((existing.name == null || existing.name.trim().isEmpty()) && name != null) {
            existing.name = name;
            existing.platform = platformOf(uuid, name);
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
        collectIrpBackupCandidates(cleanInput, candidates, customBackupsRoot, customUsercache);

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
        Platform queryPlatform = platformOfQuery(cleanInput);
        List<Candidate> sorted = new ArrayList<>(candidates.values());
        sorted.sort((a, b) -> {
            // Priority 0: la coincidencia literal manda sobre la tolerante al prefijo Floodgate.
            // Sin esto un perfil Bedrock homonimo con mas respaldos secuestra la consulta Java (#351).
            if (a.quality != b.quality) {
                return Integer.compare(a.quality.ordinal(), b.quality.ordinal());
            }

            // Priority 0.b: a igualdad de fidelidad, gana el perfil de la plataforma que se pidio.
            if (queryPlatform != Platform.UNKNOWN && a.platform != b.platform) {
                if (a.platform == queryPlatform) return -1;
                if (b.platform == queryPlatform) return 1;
            }

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

        List<ResolvedPlayer> alternates = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Candidate other = sorted.get(i);
            alternates.add(new ResolvedPlayer(other.uuid, other.name, null, other.backupCount, other.latestBackupTimestamp,
                    other.source, other.platform, other.quality, Collections.<ResolvedPlayer>emptyList()));
        }

        ResolvedPlayer result = new ResolvedPlayer(best.uuid, resolvedName, op, best.backupCount, best.latestBackupTimestamp,
                best.source, best.platform, best.quality, alternates);

        if (sorted.size() > 1 && InventoryRollbackPlus.getInstance() != null) {
            Level level = result.isCrossPlatformAmbiguous() ? Level.WARNING : Level.INFO;
            InventoryRollbackPlus.getInstance().getLogger().log(level,
                    "[PlayerResolver] Disambiguated '" + cleanInput + "': Selected UUID " + best.uuid +
                            " (" + best.platform + ", match=" + best.quality + ", " + best.backupCount + " backups, latest: "
                            + best.latestBackupTimestamp + ", source=" + best.source + ") out of " + sorted.size()
                            + " registered candidates."
                            + (result.isCrossPlatformAmbiguous()
                                ? " HOMONIMOS DE OTRA PLATAFORMA: la orden debe desempatarse por UUID."
                                : "")
            );
        }

        return Optional.of(result);
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
        // Un UUID explicito no admite homonimia: la plataforma sale del propio UUID (Floodgate usa msb=0).
        return Optional.of(new ResolvedPlayer(uuid, finalName, op, c.backupCount, c.latestBackupTimestamp, c.source,
                platformOf(uuid, finalName), MatchQuality.EXACT, Collections.<ResolvedPlayer>emptyList()));
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
                offer(candidates, p.getUniqueId(), p.getName(), "online_exact", inputName);
            }
            for (Player op : Bukkit.getOnlinePlayers()) {
                offer(candidates, op.getUniqueId(), op.getName(), "online_list", inputName);
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
                        try {
                            UUID u = UUID.fromString(obj.get("uuid").getAsString());
                            offer(candidates, u, entryName, "usercache", inputName);
                        } catch (IllegalArgumentException ignored) {}
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
                    if (name != null && uuidStr != null) {
                        try {
                            UUID u = UUID.fromString(uuidStr);
                            offer(candidates, u, name, "bentobox", inputName);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void collectIrpBackupCandidates(String inputName, Map<UUID, Candidate> candidates, File customBackupsRoot, File customUsercache) {
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
                        String knownName = null;
                        try {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                            if (op != null) knownName = op.getName();
                        } catch (Throwable ignored) {}
                        if (knownName == null) {
                            // Un perfil Bedrock rara vez tiene nombre en la cache de Bukkit:
                            // sin este respaldo su carpeta de backups quedaba irresoluble por nombre (#351).
                            Candidate probe = new Candidate(u, null, "irp_backups");
                            findNameInUsercache(u, probe, customUsercache);
                            knownName = probe.name;
                        }
                        offer(candidates, u, knownName, "irp_backups", inputName);
                    }
                }
            }
        }
    }

    private static void collectBukkitOfflineCandidates(String inputName, Map<UUID, Candidate> candidates) {
        try {
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.hasPlayedBefore()) {
                    offer(candidates, op.getUniqueId(), op.getName(), "bukkit_offline", inputName);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Fidelidad de la coincidencia. La distincion importa: tolerar el prefijo Floodgate
     * permite encontrar al Bedrock, pero NO debe empatar con el Java que se llama igual (#351).
     */
    public static MatchQuality matchQuality(String registeredName, String queryName) {
        if (registeredName == null || queryName == null) return MatchQuality.NONE;
        String registered = registeredName.trim();
        String query = queryName.trim();
        if (registered.isEmpty() || query.isEmpty()) return MatchQuality.NONE;

        if (registered.equalsIgnoreCase(query)) return MatchQuality.EXACT;

        // Bedrock Floodgate prefix handling (e.g. .Pasiente vs Pasiente)
        int plen = BEDROCK_PREFIX.length();
        if (registered.startsWith(BEDROCK_PREFIX) && registered.substring(plen).equalsIgnoreCase(query)) {
            return MatchQuality.PREFIX;
        }
        if (query.startsWith(BEDROCK_PREFIX) && query.substring(plen).equalsIgnoreCase(registered)) {
            return MatchQuality.PREFIX;
        }

        return MatchQuality.NONE;
    }

    public static boolean matchesPlayerName(String registeredName, String queryName) {
        return matchQuality(registeredName, queryName) != MatchQuality.NONE;
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
