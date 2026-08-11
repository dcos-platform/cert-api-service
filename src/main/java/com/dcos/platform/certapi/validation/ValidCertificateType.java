package com.dcos.platform.certapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = CertificateTypeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCertificateType {

    String message() default "Invalid certificate type. Allowed values: TLS, CLIENT, CA, CODE_SIGNING";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
