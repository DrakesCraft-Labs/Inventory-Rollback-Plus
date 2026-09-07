package com.nuclyon.technicallycoded.inventoryrollback.restore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerResolverTest {

    @TempDir
    Path tempDir;

    private File usercacheFile;
    private File bentoBoxNamesDir;
    private File backupsRootDir;

    private final UUID pasientePremium = UUID.fromString("7ddf2641-14fb-4c1f-8499-aa5ff6c6b295");
    private final UUID pasienteBedrock = UUID.fromString("00000000-0000-0000-0009-01f2c0de747c");

    @BeforeEach
    void setup() throws IOException {
        usercacheFile = tempDir.resolve("usercache.json").toFile();
        bentoBoxNamesDir = tempDir.resolve("BentoBox/database/Names").toFile();
        bentoBoxNamesDir.mkdirs();

        backupsRootDir = tempDir.resolve("backups").toFile();
        File deathsDir = new File(backupsRootDir, "deaths");
        deathsDir.mkdirs();

        // Write sample usercache.json with Pasiente premium and Pasiente bedrock
        String usercacheContent = "[\n" +
                "  {\"uuid\":\"" + pasientePremium + "\",\"name\":\"Pasiente\",\"expiresOn\":\"2026-10-06 02:51:10 -0300\"},\n" +
                "  {\"uuid\":\"" + pasienteBedrock + "\",\"name\":\".Pasiente8934\",\"expiresOn\":\"2026-08-26 20:00:32 -0300\"},\n" +
                "  {\"uuid\":\"97937a16-9b51-3e21-bbb2-a5eef8cfff50\",\"name\":\"JackStar6677\",\"expiresOn\":\"2026-10-06 02:17:58 -0300\"}\n" +
                "]";
        try (FileWriter writer = new FileWriter(usercacheFile)) {
            writer.write(usercacheContent);
        }

        // Create mock IRP backups for Pasiente Premium
        File pasienteBackups = new File(deathsDir, pasientePremium.toString());
        pasienteBackups.mkdirs();
        new File(pasienteBackups, "1788798000000.yml").createNewFile();
        new File(pasienteBackups, "1788799000000.yml").createNewFile();
    }

    @Test
    void rejectsUnregisteredPlayerWithoutGeneratingSyntheticOfflineUuid() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer("NonExistentPlayer12345", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertFalse(res.isPresent(), "Unregistered player must NEVER be resolved with a fake synthetic offline UUID!");
    }

    @Test
    void resolvesRegisteredUserFromUsercache() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer("JackStar6677", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(UUID.fromString("97937a16-9b51-3e21-bbb2-a5eef8cfff50"), res.get().getUuid());
        assertEquals("JackStar6677", res.get().getName());
    }

    @Test
    void disambiguatesPasienteToRegisteredUuidWithLegitimateBackups() {
        // Pasiente has two profiles: Premium (with 2 backups) vs Bedrock (0 backups)
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer("Pasiente", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(pasientePremium, res.get().getUuid(), "Must select the UUID registered with legitimate backup history!");
        assertEquals(2, res.get().getBackupCount());
        assertEquals(1788799000000L, res.get().getLatestBackupTimestamp());
    }

    @Test
    void handlesFloodgateBedrockPrefixMatching() {
        assertTrue(PlayerResolver.matchesPlayerName(".Pasiente", "Pasiente"));
        assertTrue(PlayerResolver.matchesPlayerName("Pasiente", ".Pasiente"));
        assertTrue(PlayerResolver.matchesPlayerName("JackStar6677", "jackstar6677"));
        assertFalse(PlayerResolver.matchesPlayerName("OtherPlayer", "Pasiente"));
    }

    @Test
    void parsesBoth36And32CharUuids() {
        UUID u36 = PlayerResolver.tryParseUuid("7ddf2641-14fb-4c1f-8499-aa5ff6c6b295");
        assertEquals(pasientePremium, u36);

        UUID u32 = PlayerResolver.tryParseUuid("7ddf264114fb4c1f8499aa5ff6c6b295");
        assertEquals(pasientePremium, u32);

        assertNull(PlayerResolver.tryParseUuid("not-a-uuid"));
    }

    @Test
    void resolvesByDirectRegisteredUuid() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer(pasientePremium.toString(), usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(pasientePremium, res.get().getUuid());
        assertEquals("Pasiente", res.get().getName());
        assertEquals(2, res.get().getBackupCount());
    }

    @Test
    void rejectsUnknownUuidWithoutBackupsOrRegistration() {
        UUID random = UUID.randomUUID();
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer(random.toString(), usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertFalse(res.isPresent(), "Random unknown UUID without backups or registration must be rejected!");
    }
}
