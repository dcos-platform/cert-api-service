package com.dcos.platform.certapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates that a subject string is a well-formed distinguished name with a CN component. */
@Documented
@Constraint(validatedBy = SubjectFormatValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSubjectFormat {

    String message() default "Subject must be a well-formed distinguished name with CN component";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
