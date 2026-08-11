package com.dcos.platform.certapi.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class CertificateTypeValidator implements ConstraintValidator<ValidCertificateType, String> {

    private static final Set<String> ALLOWED_TYPES = Set.of("TLS", "CLIENT", "CA", "CODE_SIGNING");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return ALLOWED_TYPES.contains(value.toUpperCase());
    }
}
