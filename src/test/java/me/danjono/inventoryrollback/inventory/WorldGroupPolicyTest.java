package me.danjono.inventoryrollback.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldGroupPolicyTest {

    @Test
    void resolvesProtectedModalitiesWithoutExistingConfigMigration() {
        assertEquals("laboratorio", WorldGroupPolicy.builtInGroupOf("laboratorio"));
        assertEquals("laboratorio", WorldGroupPolicy.builtInGroupOf("laboratorio_nether"));
        assertEquals("clasico", WorldGroupPolicy.builtInGroupOf("clasico_the_end"));
        assertEquals("skyblock", WorldGroupPolicy.builtInGroupOf("bskyblock_world_nether"));
        assertEquals("oneblock", WorldGroupPolicy.builtInGroupOf("oneblock_world_the_end"));
    }

    @Test
    void leavesSurvivalAndAddonWorldsForConfiguredFallback() {
        assertNull(WorldGroupPolicy.builtInGroupOf("world"));
        assertNull(WorldGroupPolicy.builtInGroupOf("world_galactifun_mars"));
        assertNull(WorldGroupPolicy.builtInGroupOf("boss_dimension"));
    }

    @Test
    void rejectsMissingWorldMetadataIntoUnknownGroup() {
        assertEquals("unknown", WorldGroupPolicy.builtInGroupOf(null));
        assertEquals("unknown", WorldGroupPolicy.builtInGroupOf("  "));
    }
}
