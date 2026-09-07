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
    /** Cuenta Bedrock cuyo gamertag es homonimo exacto del Java "Pasiente" salvo por el prefijo. */
    private final UUID pasienteBedrockHomonimo = UUID.fromString("00000000-0000-0000-0009-01f2c0de9999");

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
                "  {\"uuid\":\"" + pasienteBedrockHomonimo + "\",\"name\":\".Pasiente\",\"expiresOn\":\"2026-08-26 20:00:32 -0300\"},\n" +
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

        // El homonimo Bedrock tiene MAS respaldos y mas recientes: si el desempate solo mirara
        // el historial, secuestraria cualquier consulta por el nombre Java (#351).
        File bedrockBackups = new File(deathsDir, pasienteBedrockHomonimo.toString());
        bedrockBackups.mkdirs();
        new File(bedrockBackups, "1788800000000.yml").createNewFile();
        new File(bedrockBackups, "1788801000000.yml").createNewFile();
        new File(bedrockBackups, "1788802000000.yml").createNewFile();
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
        // Pasiente tiene perfiles homonimos: Premium Java (2 respaldos) vs Bedrock (3 respaldos)
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
    void exactJavaNameBeatsBedrockHomonymWithMoreBackups() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer("Pasiente", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(pasientePremium, res.get().getUuid(),
                "La coincidencia literal Java debe ganar al homonimo Bedrock aunque este tenga mas respaldos");
        assertEquals(PlayerResolver.Platform.JAVA, res.get().getPlatform());
        assertEquals(PlayerResolver.MatchQuality.EXACT, res.get().getQuality());
    }

    @Test
    void dottedQueryResolvesToBedrockProfile() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer(".Pasiente", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(pasienteBedrockHomonimo, res.get().getUuid(), "El prefijo punto identifica sin ambiguedad a la cuenta Bedrock");
        assertEquals(PlayerResolver.Platform.BEDROCK, res.get().getPlatform());
        assertTrue(res.get().isBedrock());
        assertEquals(3, res.get().getBackupCount());
    }

    @Test
    void detectsFloodgateSyntheticUuids() {
        assertTrue(PlayerResolver.isFloodgateUuid(pasienteBedrock));
        assertTrue(PlayerResolver.isFloodgateUuid(pasienteBedrockHomonimo));
        assertFalse(PlayerResolver.isFloodgateUuid(pasientePremium));
        assertFalse(PlayerResolver.isFloodgateUuid(null));
        assertFalse(PlayerResolver.isFloodgateUuid(new UUID(0L, 0L)), "El UUID nulo no es un perfil Floodgate");
    }

    @Test
    void classifiesPlatformByUuidAndNamePrefix() {
        assertEquals(PlayerResolver.Platform.BEDROCK, PlayerResolver.platformOf(pasienteBedrock, ".Pasiente8934"));
        assertEquals(PlayerResolver.Platform.BEDROCK, PlayerResolver.platformOf(pasientePremium, ".Pasiente"));
        assertEquals(PlayerResolver.Platform.JAVA, PlayerResolver.platformOf(pasientePremium, "Pasiente"));
        assertEquals(PlayerResolver.Platform.BEDROCK, PlayerResolver.platformOfQuery(".Pasiente"));
        assertEquals(PlayerResolver.Platform.JAVA, PlayerResolver.platformOfQuery("Pasiente"));
        assertEquals(PlayerResolver.Platform.UNKNOWN, PlayerResolver.platformOfQuery("  "));
        assertFalse(PlayerResolver.isBedrockName("."), "Un punto suelto no es un gamertag Bedrock");
    }

    @Test
    void gradesMatchQualityBetweenExactAndPrefix() {
        assertEquals(PlayerResolver.MatchQuality.EXACT, PlayerResolver.matchQuality("Pasiente", "pasiente"));
        assertEquals(PlayerResolver.MatchQuality.PREFIX, PlayerResolver.matchQuality(".Pasiente", "Pasiente"));
        assertEquals(PlayerResolver.MatchQuality.PREFIX, PlayerResolver.matchQuality("Pasiente", ".Pasiente"));
        assertEquals(PlayerResolver.MatchQuality.EXACT, PlayerResolver.matchQuality(".Pasiente", ".pasiente"));
        assertEquals(PlayerResolver.MatchQuality.NONE, PlayerResolver.matchQuality("OtherPlayer", "Pasiente"));
    }

    @Test
    void doesNotFlagAmbiguityWhenOnlyOnePlatformMatchesLiterally() {
        // "Pasiente" coincide literalmente solo con el Java; el Bedrock coincide con menor fidelidad.
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer("Pasiente", usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertFalse(res.get().isCrossPlatformAmbiguous(),
                "Con una unica coincidencia literal no hay empate que desempatar");
        assertFalse(res.get().getAlternates().isEmpty(), "Los homonimos siguen expuestos para auditoria");
    }

    @Test
    void directUuidQueryIsNeverAmbiguous() {
        Optional<PlayerResolver.ResolvedPlayer> res = PlayerResolver.resolvePlayer(pasienteBedrockHomonimo.toString(), usercacheFile, bentoBoxNamesDir, backupsRootDir);
        assertTrue(res.isPresent());
        assertEquals(PlayerResolver.Platform.BEDROCK, res.get().getPlatform());
        assertFalse(res.get().isCrossPlatformAmbiguous());
        assertTrue(res.get().getAlternates().isEmpty());
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
