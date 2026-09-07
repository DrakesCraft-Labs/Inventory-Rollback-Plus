package com.nuclyon.technicallycoded.inventoryrollback.restore;

import com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd;
import me.danjono.inventoryrollback.data.LogType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PendingRestoreManagerTest {

    @TempDir
    Path tempDir;

    private File pendingFile;
    private PendingRestoreManager manager;

    private final UUID testUuid = UUID.fromString("7ddf2641-14fb-4c1f-8499-aa5ff6c6b295");

    @BeforeEach
    void setup() {
        pendingFile = tempDir.resolve("pending_restores.yml").toFile();
        manager = new PendingRestoreManager(pendingFile);
    }

    @Test
    void queuesAndPersistsPendingRestore() {
        PendingRestoreManager.PendingRestore restore = new PendingRestoreManager.PendingRestore(
                testUuid,
                "Pasiente",
                LogType.DEATH,
                1788798000000L,
                "bskyblock_world",
                "skyblock",
                false,
                false,
                "CONSOLE",
                System.currentTimeMillis()
        );

        manager.queueRestore(restore);

        // File should exist on disk
        assertTrue(pendingFile.exists());

        // Reload from fresh manager instance
        PendingRestoreManager fresh = new PendingRestoreManager(pendingFile);
        Optional<PendingRestoreManager.PendingRestore> loaded = fresh.getPending(testUuid);

        assertTrue(loaded.isPresent());
        assertEquals("Pasiente", loaded.get().getPlayerName());
        assertEquals(LogType.DEATH, loaded.get().getLogType());
        assertEquals(1788798000000L, loaded.get().getTimestamp());
        assertEquals("skyblock", loaded.get().getTargetGroup());
        assertFalse(loaded.get().isRestoreEnder());
        assertFalse(loaded.get().isForce());
        assertEquals("CONSOLE", loaded.get().getQueuedBy());
    }

    @Test
    void cancelsPendingRestoreSuccessfully() {
        PendingRestoreManager.PendingRestore restore = new PendingRestoreManager.PendingRestore(
                testUuid,
                "Pasiente",
                LogType.QUIT,
                1788798000000L,
                "world",
                "survival",
                true,
                true,
                "Jack",
                System.currentTimeMillis()
        );

        manager.queueRestore(restore);
        assertTrue(manager.getPending(testUuid).isPresent());

        boolean cancelled = manager.cancelPending(testUuid);
        assertTrue(cancelled);
        assertFalse(manager.getPending(testUuid).isPresent());

        // Verify persistence after cancel
        PendingRestoreManager fresh = new PendingRestoreManager(pendingFile);
        assertFalse(fresh.getPending(testUuid).isPresent());
    }

    // ── Ticket #356: contrato de permisos lectura vs mutacion ──────────────────

    @Test
    void viewbackupsPuedeMirarPeroNoRestaurar() {
        // Jugador con SOLO inventoryrollbackplus.viewbackups
        assertTrue(RestoreSubCmd.mayView(true, true, false),
                "viewbackups debe poder abrir el comando y listar respaldos");
        assertFalse(RestoreSubCmd.mayMutate(true, false),
                "viewbackups NO puede restaurar: contradice plugin.yml y el README");
    }

    @Test
    void restorePuedeMirarYMutar() {
        assertTrue(RestoreSubCmd.mayView(true, false, true));
        assertTrue(RestoreSubCmd.mayMutate(true, true));
    }

    @Test
    void sinPermisosNoEntraAlComando() {
        assertFalse(RestoreSubCmd.mayView(true, false, false));
        assertFalse(RestoreSubCmd.mayMutate(true, false));
    }

    @Test
    void consolaConservaAccesoTotal() {
        // La consola/RCON no tiene permisos Bukkit: no debe quedar bloqueada por el fix.
        assertTrue(RestoreSubCmd.mayView(false, false, false));
        assertTrue(RestoreSubCmd.mayMutate(false, false));
        assertTrue(RestoreSubCmd.mayForce(false, false));
    }

    @Test
    void forceExigePermisoDeCruceEntreModalidades() {
        assertFalse(RestoreSubCmd.mayForce(true, false),
                "--force salta el aislamiento entre modalidades y no puede ser gratis");
        assertTrue(RestoreSubCmd.mayForce(true, true));
    }

    // ── Ticket #349/#356: contrato de modalidad de la cola offline ─────────────

    @Test
    void colaOfflineNoSeAplicaEnLaModalidadEquivocada() {
        // Copia de clasico, jugador aterrizado en el Lobby tras conectar.
        assertFalse(PendingRestoreManager.canApplyInGroup("survival", "clasico", false));
        assertTrue(PendingRestoreManager.canApplyInGroup("clasico", "clasico", false));
    }

    @Test
    void colaOfflineIgnoraMayusculasYGrupoDesconocido() {
        assertTrue(PendingRestoreManager.canApplyInGroup("SkyBlock", "skyblock", false));
        assertTrue(PendingRestoreManager.canApplyInGroup("survival", "unknown", false));
        assertTrue(PendingRestoreManager.canApplyInGroup("survival", null, false));
    }

    @Test
    void forceAplicaEnCualquierModalidad() {
        assertTrue(PendingRestoreManager.canApplyInGroup("survival", "oneblock", true));
    }

    @Test
    void parsesTimeDeltasCorrectly() {
        assertEquals(10 * 60 * 1000L, com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd.parseTimeDelta("10m"));
        assertEquals(30 * 60 * 1000L, com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd.parseTimeDelta("30m"));
        assertEquals(2 * 3600 * 1000L, com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd.parseTimeDelta("2h"));
        assertEquals(24 * 3600 * 1000L, com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd.parseTimeDelta("1d"));
        assertEquals(15 * 60 * 1000L, com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback.RestoreSubCmd.parseTimeDelta("15"));
    }
}
