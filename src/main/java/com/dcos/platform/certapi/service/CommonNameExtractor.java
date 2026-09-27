package com.dcos.platform.certapi.service;

import java.util.Optional;

/**
 * Derives a certificate common name from a distinguished name (DN) or accepts an explicitly
 * provided value.
 *
 * <p>Parsing follows the X.500 relative distinguished name (RDN) format: extracts the value of the
 * {@code CN} (common name) attribute if one exists in the DN. Handles DN values in the format
 * {@code CN=value,...} where the CN value can optionally be quoted.
 */
public class CommonNameExtractor {

    /**
     * Determines a common name from the provided request value and subject DN.
     *
     * @param requestedCommonName the common name explicitly provided in the request (may be null)
     * @param subject the distinguished name, from which CN is parsed if requestedCommonName is
     *     absent
     * @return the determined common name
     * @throws IllegalArgumentException if neither the request value nor a CN in the subject is
     *     available
     */
    public static String extract(String requestedCommonName, String subject) {
        if (requestedCommonName != null && !requestedCommonName.isBlank()) {
            return requestedCommonName;
        }

        return parseCommonNameFromSubject(subject)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No common name provided and none found in subject DN"));
    }

    /**
     * Extracts the CN (common name) RDN from a distinguished name.
     *
     * <p>Example: {@code CN=example.com,OU=services,O=org} → {@code example.com}
     *
     * @param subject the distinguished name
     * @return the common name value, or empty if no CN is found
     */
    private static Optional<String> parseCommonNameFromSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }

        for (String rdn : subject.split(",")) {
            String trimmed = rdn.trim();
            if (trimmed.toUpperCase().startsWith("CN")) {
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex != -1) {
                    String value = trimmed.substring(eqIndex + 1).trim();
                    // Remove surrounding quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isBlank()) {
                        return Optional.of(value);
                    }
                }
            }
        }

        return Optional.empty();
    }
}
