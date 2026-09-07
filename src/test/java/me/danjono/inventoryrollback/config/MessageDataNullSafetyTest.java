package me.danjono.inventoryrollback.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDataNullSafetyTest {

    @Test
    void testGetNoBackupErrorWithUninitializedConfig() {
        assertDoesNotThrow(() -> {
            String msgNull = MessageData.getNoBackupError(null);
            assertNotNull(msgNull);
        });
    }

    @Test
    void testGetNoBackupErrorWithConfiguredMessage() {
        MessageData.setNoBackupError("No se encontraron respaldos para %NAME%");

        assertDoesNotThrow(() -> {
            String msgNull = MessageData.getNoBackupError(null);
            assertNotNull(msgNull);
            assertTrue(msgNull.contains("Desconocido"));

            String msgEmpty = MessageData.getNoBackupError("");
            assertNotNull(msgEmpty);
            assertTrue(msgEmpty.contains("Desconocido"));

            String msgValid = MessageData.getNoBackupError("Pasiente");
            assertNotNull(msgValid);
            assertTrue(msgValid.contains("Pasiente"));
        });
    }

    @Test
    void testOtherPlayerMessagesWithNullName() {
        assertDoesNotThrow(() -> {
            assertNotNull(MessageData.getNotOnlineError(null));
            assertNotNull(MessageData.getForceBackupPlayer(null));
            assertNotNull(MessageData.getForceBackupError(null));
            assertNotNull(MessageData.getMainInventoryRestored(null));
            assertNotNull(MessageData.getMainInventoryRestoredPlayer(null));
            assertNotNull(MessageData.getMainInventoryNotOnline(null));
            assertNotNull(MessageData.getEnderChestRestored(null));
            assertNotNull(MessageData.getEnderChestRestoredPlayer(null));
            assertNotNull(MessageData.getEnderChestNotOnline(null));
            assertNotNull(MessageData.getHealthRestored(null));
            assertNotNull(MessageData.getHealthRestoredPlayer(null));
            assertNotNull(MessageData.getHealthNotOnline(null));
            assertNotNull(MessageData.getHungerRestored(null));
            assertNotNull(MessageData.getHungerRestoredPlayer(null));
            assertNotNull(MessageData.getHungerNotOnline(null));
            assertNotNull(MessageData.getExperienceRestored(null, 10));
            assertNotNull(MessageData.getExperienceRestoredPlayer(null, 10));
            assertNotNull(MessageData.getExperienceNotOnlinePlayer(null));
        });
    }
}
