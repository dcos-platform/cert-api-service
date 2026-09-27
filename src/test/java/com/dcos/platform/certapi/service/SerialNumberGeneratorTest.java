package com.dcos.platform.certapi.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Year;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SerialNumberGeneratorTest {

    private final SerialNumberGenerator generator = new SerialNumberGenerator();

    @Test
    void generatesValidFormat() {
        String serial = generator.generate();
        String year = String.valueOf(Year.now().getValue());
        assertTrue(
                serial.startsWith("DCOS-" + year + "-"), "Serial should start with DCOS-<year>-");
        assertEquals(22, serial.length(), "Serial should be 22 chars: DCOS-YYYY-<12hex>");
    }

    @Test
    void generatesUppercaseHexadecimal() {
        String serial = generator.generate();
        String hexPart = serial.substring(10);
        assertTrue(hexPart.matches("[0-9A-F]{12}"), "Should contain 12 uppercase hex chars");
    }

    @Test
    void producesUniqueValuesOnRepeatedCalls() {
        Set<String> serials = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String serial = generator.generate();
            assertTrue(serials.add(serial), "Generated serial should be unique: " + serial);
        }
    }

    @Test
    void includesCurrentYear() {
        String year = String.valueOf(Year.now().getValue());
        String serial = generator.generate();
        assertTrue(serial.contains(year), "Serial should include current year");
    }
}
