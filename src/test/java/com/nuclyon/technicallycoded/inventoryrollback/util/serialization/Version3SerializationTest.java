package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class Version3SerializationTest {

    @BeforeAll
    public static void setUpServer() {
        if (!MockBukkit.isMocked()) {
            MockBukkit.mock();
        }
    }

    @AfterAll
    public static void tearDownServer() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    public void testSerializeEmptyArray() {
        ItemStack[] items = new ItemStack[0];
        String serialized = Version3Serialization.serialize(items);
        assertNotNull(serialized, "Serialized data should not be null");

        DeserializationResult result = Version3Serialization.deserialize(serialized);
        assertNull(result.getErrorMessage(), "There should be no error message during deserialization");
        assertNotNull(result.getItems(), "The deserialized array should not be null");
        assertEquals(0, result.getItems().length, "The length of the deserialized array should be 0");
    }

    @Test
    public void testSerializeArrayWithNullItems() {
        ItemStack[] items = new ItemStack[] { null, null };
        String serialized = Version3Serialization.serialize(items);
        assertNotNull(serialized, "Serialized data should not be null");

        DeserializationResult result = Version3Serialization.deserialize(serialized);
        assertNull(result.getErrorMessage(), "There should be no error message during deserialization");
        assertNotNull(result.getItems(), "The deserialized array should not be null");
        assertEquals(2, result.getItems().length, "The deserialized array should have length 2");
        assertNull(result.getItems()[0], "First item should be null");
        assertNull(result.getItems()[1], "Second item should be null");
    }

    @Test
    public void testDeserializeCorruptedData() {
        String corruptedData = "not_base64_encoded_data";
        DeserializationResult result = Version3Serialization.deserialize(corruptedData);
        assertNotNull(result.getErrorMessage(), "An error message is expected for corrupted data");
        assertNull(result.getItems(), "ItemStacks array should be null when deserialization fails");
    }

    @Test
    public void testRoundTripSlimefunPdcItems() {
        // 1. Crear item Slimefun con tags PDC reales de Paper/Bukkit
        ItemStack slimefunItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = slimefunItem.getItemMeta();
        assertNotNull(meta, "ItemMeta should be obtainable from MockBukkit");

        meta.setDisplayName("§bCarbonado Blade");
        meta.setLore(Arrays.asList("§7A powerful Slimefun blade", "§eEnergy: 50000 / 50000 J"));

        NamespacedKey sfItemKey = new NamespacedKey("slimefun", "slimefun_item");
        NamespacedKey sfEnergyKey = new NamespacedKey("slimefun", "stored_energy");
        NamespacedKey sfBackpackKey = new NamespacedKey("slimefun", "backpack_id");

        meta.getPersistentDataContainer().set(sfItemKey, PersistentDataType.STRING, "CARBONADO_SWORD");
        meta.getPersistentDataContainer().set(sfEnergyKey, PersistentDataType.INTEGER, 50000);
        meta.getPersistentDataContainer().set(sfBackpackKey, PersistentDataType.STRING, "sf-uuid-backpack-nested-99");
        slimefunItem.setItemMeta(meta);

        // 2. Crear array con slot null, item normal y item con PDC
        ItemStack vanillaItem = new ItemStack(Material.GOLDEN_APPLE, 32);
        ItemStack[] originalArray = new ItemStack[] { slimefunItem, null, vanillaItem };

        // 3. Serializar con Version3Serialization (GZIP + V2)
        String serialized = Version3Serialization.serialize(originalArray);
        assertNotNull(serialized, "Serialized string should not be null");
        assertFalse(serialized.isEmpty(), "Serialized string should not be empty");

        // 4. Deserializar
        DeserializationResult result = Version3Serialization.deserialize(serialized);
        assertNull(result.getErrorMessage(), "Deserialization should succeed without errors");
        assertNotNull(result.getItems(), "Deserialized items should not be null");
        assertEquals(3, result.getItems().length, "Array length should match exactly");

        // 5. Validar preservacion slot a slot
        assertNull(result.getItems()[1], "Slot 1 should remain null");

        ItemStack restoredVanilla = result.getItems()[2];
        assertNotNull(restoredVanilla, "Slot 2 should contain the vanilla item");
        assertEquals(Material.GOLDEN_APPLE, restoredVanilla.getType());
        assertEquals(32, restoredVanilla.getAmount());

        ItemStack restoredSf = result.getItems()[0];
        assertNotNull(restoredSf, "Slot 0 should contain the Slimefun item");
        assertEquals(Material.DIAMOND_SWORD, restoredSf.getType());

        ItemMeta restoredMeta = restoredSf.getItemMeta();
        assertNotNull(restoredMeta, "Restored ItemMeta should not be null");
        assertEquals("§bCarbonado Blade", restoredMeta.getDisplayName(), "Display name must be preserved");
        assertEquals(Arrays.asList("§7A powerful Slimefun blade", "§eEnergy: 50000 / 50000 J"), restoredMeta.getLore(), "Lore must be preserved");

        // Validar que el PersistentDataContainer conserva el 100% de los tags
        assertEquals("CARBONADO_SWORD", restoredMeta.getPersistentDataContainer().get(sfItemKey, PersistentDataType.STRING), "slimefun_item tag must match");
        assertEquals(Integer.valueOf(50000), restoredMeta.getPersistentDataContainer().get(sfEnergyKey, PersistentDataType.INTEGER), "stored_energy tag must match");
        assertEquals("sf-uuid-backpack-nested-99", restoredMeta.getPersistentDataContainer().get(sfBackpackKey, PersistentDataType.STRING), "backpack_id tag must match");
    }
}
