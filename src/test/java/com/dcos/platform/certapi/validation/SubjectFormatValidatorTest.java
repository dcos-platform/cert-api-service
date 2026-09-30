package com.dcos.platform.certapi.validation;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SubjectFormatValidatorTest {

    private final SubjectFormatValidator validator = new SubjectFormatValidator();
    private final ConstraintValidatorContext context =
            Mockito.mock(ConstraintValidatorContext.class);

    @Test
    void acceptsValidDistinguishedNameWithCN() {
        assertThat(validator.isValid("CN=example.com,OU=Engineering,O=DCOS", context)).isTrue();
    }

    @Test
    void acceptsDistinguishedNameWithMultipleComponents() {
        assertThat(
                        validator.isValid(
                                "C=US,ST=California,L=San Francisco,O=DCOS,CN=example.com",
                                context))
                .isTrue();
    }

    @Test
    void acceptsDistinguishedNameWithEscapedComma() {
        assertThat(validator.isValid("CN=example\\,corp.com,OU=Engineering,O=DCOS", context))
                .isTrue();
    }

    @Test
    void rejectsSubjectWithoutCN() {
        assertThat(validator.isValid("OU=Engineering,O=DCOS", context)).isFalse();
    }

    @Test
    void rejectsMalformedDistinguishedName() {
        assertThat(validator.isValid("invalid-subject-without-dn-format", context)).isFalse();
    }

    @Test
    void acceptsNullSubject() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void acceptsBlankSubject() {
        assertThat(validator.isValid("   ", context)).isTrue();
    }

    @Test
    void rejectsLowercaseAttributeType() {
        assertThat(validator.isValid("cn=example.com", context)).isFalse();
    }

    @Test
    void rejectsEmptyAttributeValue() {
        assertThat(validator.isValid("CN=", context)).isFalse();
    }

    @Test
    void rejectsTrailingSeparator() {
        assertThat(validator.isValid("CN=example.com,", context)).isFalse();
    }

    @Test
    void rejectsSecondUnescapedEqualsInValue() {
        assertThat(validator.isValid("CN=a=b", context)).isFalse();
    }

    @Test
    void acceptsEscapedEqualsInValue() {
        assertThat(validator.isValid("CN=a\\=b,O=DCOS", context)).isTrue();
    }

    /**
     * A long subject reaches this validator regardless of {@code @Size}, because Bean Validation
     * evaluates every constraint on a value rather than stopping at the first failure. Matching a
     * quantified group recurses per iteration, so the regular expression this validator used to
     * rely on threw {@link StackOverflowError} here.
     */
    @Test
    void acceptsVeryLongValueWithoutOverflowingTheStack() {
        assertThat(validator.isValid("CN=" + "a".repeat(100_000), context)).isTrue();
    }

    @Test
    void rejectsLongEscapeHeavyValueWithoutExcessiveBacktracking() {
        assertThat(validator.isValid("CN=" + "\\a".repeat(50_000) + "=b", context)).isFalse();
    }
}
