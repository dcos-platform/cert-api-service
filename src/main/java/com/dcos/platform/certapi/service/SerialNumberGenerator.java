package com.dcos.platform.certapi.service;

import java.security.SecureRandom;
import java.time.Year;
import org.springframework.stereotype.Component;

/**
 * Generates certificate serial numbers in the format {@code DCOS-<yyyy>-<12 uppercase hex>}.
 * Handles collisions by retrying with fresh random values.
 */
@Component
public class SerialNumberGenerator {

    private static final String PREFIX = "DCOS";
    private static final int HEX_CHARS = 12;
    private static final int MAX_RETRIES = 10;
    private static final String HEX_CHARS_UPPER = "0123456789ABCDEF";

    private final SecureRandom random;

    public SerialNumberGenerator() {
        this.random = new SecureRandom();
    }

    /**
     * Generates a serial number. The format is always valid; this method does not interact with the
     * database. The caller is responsible for persisting the value and retrying with a fresh call
     * if a uniqueness constraint is violated.
     *
     * @return a serial number in the format {@code DCOS-<yyyy>-<12 uppercase hex>}
     */
    public String generate() {
        String year = String.valueOf(Year.now().getValue());
        String randomHex = generateRandomHex();
        return PREFIX + "-" + year + "-" + randomHex;
    }

    private String generateRandomHex() {
        StringBuilder sb = new StringBuilder(HEX_CHARS);
        for (int i = 0; i < HEX_CHARS; i++) {
            sb.append(HEX_CHARS_UPPER.charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}
