package com.dcos.platform.certapi.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that a subject string is a well-formed distinguished name with a CN component.
 *
 * <p>A subject is a comma-separated sequence of relative distinguished names, each {@code TYPE=
 * value} with an uppercase type. A backslash escapes the following character, so a value may
 * contain a comma or an equals sign when escaped.
 *
 * <p>Deliberately not implemented as one regular expression over the whole subject. Matching a
 * quantified group such as {@code (?:[^,=]|\\.)+} recurses once per iteration in {@code
 * java.util.regex}, so a long subject overflows the stack — and {@code @Size} on the field does not
 * prevent it, because Bean Validation evaluates every constraint on a value rather than stopping at
 * the first failure. The scan below is iterative and linear in the length of the input. The only
 * pattern retained applies to an attribute type, and is a plain character class, which the matcher
 * implements without recursion.
 */
public class SubjectFormatValidator implements ConstraintValidator<ValidSubjectFormat, String> {

    private static final Pattern ATTRIBUTE_TYPE = Pattern.compile("[A-Z]+");
    private static final String COMMON_NAME_TYPE = "CN";
    private static final char ESCAPE = '\\';
    private static final char SEPARATOR = ',';
    private static final char ASSIGNMENT = '=';
    private static final int NOT_FOUND = -1;

    /**
     * Reports whether the value is a well-formed distinguished name containing a CN.
     *
     * <p>A null or blank subject is treated as valid, leaving presence to be enforced by the
     * separate {@code @NotBlank} constraint on the field.
     *
     * @param value the subject to validate
     * @param context the constraint context, unused
     * @return true if every relative distinguished name is well formed and one of them is a CN
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        boolean hasCommonName = false;
        for (String rdn : splitOnUnescapedSeparators(value)) {
            String type = attributeTypeOf(rdn);
            if (type == null) {
                return false;
            }
            hasCommonName = hasCommonName || COMMON_NAME_TYPE.equals(type);
        }
        return hasCommonName;
    }

    /** Splits on commas that are not escaped, keeping escape sequences intact. */
    private static List<String> splitOnUnescapedSeparators(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == ESCAPE) {
                escaped = true;
            } else if (c == SEPARATOR) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        parts.add(current.toString());
        return parts;
    }

    /**
     * Returns the attribute type of one relative distinguished name, or null if it is malformed.
     * Malformed means no unescaped separator, an empty type or value, a type that is not uppercase
     * letters, or a further unescaped equals sign in the value.
     */
    private static String attributeTypeOf(String rdn) {
        String trimmed = rdn.stripLeading();
        int separator = indexOfUnescaped(trimmed, ASSIGNMENT, 0);

        boolean wellFormed =
                separator > 0
                        && separator < trimmed.length() - 1
                        && indexOfUnescaped(trimmed, ASSIGNMENT, separator + 1) == NOT_FOUND;
        if (!wellFormed) {
            return null;
        }

        String type = trimmed.substring(0, separator);
        return ATTRIBUTE_TYPE.matcher(type).matches() ? type : null;
    }

    /** Index of the first unescaped occurrence of the target at or after {@code from}, or -1. */
    private static int indexOfUnescaped(String text, char target, int from) {
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == ESCAPE) {
                escaped = true;
            } else if (c == target) {
                return i;
            }
        }
        return NOT_FOUND;
    }
}
