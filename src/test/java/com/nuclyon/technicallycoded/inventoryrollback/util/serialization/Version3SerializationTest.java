package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class Version3SerializationTest {

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
}
