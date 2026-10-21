package dev.porter.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NamesTest {
    @Test
    void identifiers() {
        assertEquals("CopperPress", Names.pascal("copper_press"));
        assertEquals("Potion Brew", Names.humanize("potion_brew"));
        assertEquals("Min Value", Names.humanize("minValue"));
        assertEquals("Machines Copper Press", Names.humanize("machines/copper_press"));
        assertEquals("MachinesCopperPress", Names.pascal("machines/copper_press"));
        assertEquals("N3dPrinter", Names.pascal("3d_printer"));
        assertEquals("COPPER_PRESS", Names.constant("copper-press"));
        assertEquals("class_", Names.field("class"));
        assertEquals("steamTurbine", Names.lowerCamel("steam_turbine"));
        assertEquals("examplemod.steamTurbine", GenOptions.LegacyNames.NS_CAMEL.apply("examplemod", "steam_turbine"));
        assertEquals("steam.turbine", GenOptions.LegacyNames.PATH.apply("examplemod", "steam/turbine"));
        assertEquals("CopperPressBlock", Names.simpleName("a.b.Outer$CopperPressBlock"));
    }

    @Test
    void ranges() {
        GenOptions.IntRange r = GenOptions.IntRange.parse("50-59");
        assertEquals(10, r.size());
        assertEquals("7-7", GenOptions.IntRange.parse("7").toString());
        assertThrows(IllegalArgumentException.class, () -> GenOptions.IntRange.parse("9-3"));
    }
}
