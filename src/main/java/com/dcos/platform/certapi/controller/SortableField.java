package com.dcos.platform.certapi.controller;

/**
 * Enumeration of fields that can be used for sorting certificate queries. Only these fields are
 * allowed in sort specifications to prevent injection attacks and ensure database column
 * compatibility.
 */
public enum SortableField {
    CREATED_AT("createdAt"),
    EXPIRES_AT("expiresAt"),
    ISSUED_AT("issuedAt"),
    COMMON_NAME("commonName"),
    SUBJECT("subject"),
    STATUS("status"),
    TYPE("type");

    private final String fieldName;

    SortableField(String fieldName) {
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }

    /**
     * Returns the SortableField enum constant matching the given field name (case-sensitive).
     *
     * @param fieldName the field name to match
     * @return the matching SortableField
     * @throws IllegalArgumentException if no field matches the given name
     */
    public static SortableField fromFieldName(String fieldName) {
        for (SortableField field : values()) {
            if (field.fieldName.equals(fieldName)) {
                return field;
            }
        }
        throw new IllegalArgumentException("Invalid sort field: " + fieldName);
    }
}
