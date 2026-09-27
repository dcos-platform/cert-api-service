package com.dcos.platform.certapi.exception;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFoundReturnsNotFoundStatus() {
        UUID id = UUID.randomUUID();
        CertificateNotFoundException ex = new CertificateNotFoundException(id);

        ProblemDetail problem = handler.handleNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void handleNotFoundSetsErrorCode() {
        UUID id = UUID.randomUUID();
        CertificateNotFoundException ex = new CertificateNotFoundException(id);

        ProblemDetail problem = handler.handleNotFound(ex);

        assertThat(problem.getProperties()).containsEntry("code", ErrorCode.CERT_NOT_FOUND.name());
    }

    @Test
    void handleNotFoundIncludesExceptionMessage() {
        UUID id = UUID.randomUUID();
        CertificateNotFoundException ex = new CertificateNotFoundException(id);

        ProblemDetail problem = handler.handleNotFound(ex);

        assertThat(problem.getDetail()).contains(id.toString());
    }

    @Test
    void handleNotFoundSetsErrorType() {
        CertificateNotFoundException ex = new CertificateNotFoundException(UUID.randomUUID());

        ProblemDetail problem = handler.handleNotFound(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/not-found"));
    }

    @Test
    void handleStateExceptionReturnsConflictStatus() {
        UUID id = UUID.randomUUID();
        CertificateStateException ex = new CertificateStateException(id, "Invalid transition");

        ProblemDetail problem = handler.handleStateException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void handleStateExceptionSetsErrorCode() {
        UUID id = UUID.randomUUID();
        CertificateStateException ex = new CertificateStateException(id, "Invalid transition");

        ProblemDetail problem = handler.handleStateException(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_INVALID_STATE.name());
    }

    @Test
    void handleStateExceptionIncludesExceptionMessage() {
        UUID id = UUID.randomUUID();
        CertificateStateException ex =
                new CertificateStateException(id, "Expired certificate cannot be renewed");

        ProblemDetail problem = handler.handleStateException(ex);

        assertThat(problem.getDetail()).contains(id.toString()).contains("Expired certificate");
    }

    @Test
    void handleStateExceptionSetsErrorType() {
        CertificateStateException ex = new CertificateStateException(UUID.randomUUID(), "test");

        ProblemDetail problem = handler.handleStateException(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/invalid-state"));
    }

    @Test
    void handleDuplicateReturnsConflictStatus() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=test,O=DCOS", "TLS");

        ProblemDetail problem = handler.handleDuplicate(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void handleDuplicateSetsErrorCode() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=test,O=DCOS", "TLS");

        ProblemDetail problem = handler.handleDuplicate(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_DUPLICATE_ACTIVE.name());
    }

    @Test
    void handleDuplicateIncludesExceptionMessage() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=api,OU=services,O=DCOS", "CLIENT");

        ProblemDetail problem = handler.handleDuplicate(ex);

        assertThat(problem.getDetail()).contains("CN=api,OU=services,O=DCOS").contains("CLIENT");
    }

    @Test
    void handleDuplicateSetsErrorType() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=test,O=DCOS", "TLS");

        ProblemDetail problem = handler.handleDuplicate(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/duplicate-active"));
    }

    @Test
    void handleValidationReturnsBadRequestStatus() {
        MethodArgumentNotValidException ex =
                createValidationException("field1", "must not be blank");

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void handleValidationSetsErrorCode() {
        MethodArgumentNotValidException ex =
                createValidationException("field1", "must not be blank");

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_VALIDATION_FAILED.name());
    }

    @Test
    void handleValidationPopulatesFieldErrorsMap() {
        MethodArgumentNotValidException ex =
                createValidationExceptionWithMultipleErrors(
                        Map.of(
                                "subject",
                                "must not be blank",
                                "type",
                                "must be one of TLS, CLIENT"));

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getProperties())
                .containsEntry(
                        "errors",
                        Map.of(
                                "subject",
                                "must not be blank",
                                "type",
                                "must be one of TLS, CLIENT"));
    }

    @Test
    void handleValidationFieldErrorsMapKeyedByFieldName() {
        MethodArgumentNotValidException ex =
                createValidationExceptionWithMultipleErrors(
                        Map.of(
                                "expiryDate",
                                "must be in the future",
                                "commonName",
                                "must match pattern"));

        ProblemDetail problem = handler.handleValidation(ex);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) problem.getProperties().get("errors");
        assertThat(errors).containsKeys("expiryDate", "commonName");
    }

    @Test
    void handleValidationSetsErrorType() {
        MethodArgumentNotValidException ex = createValidationException("field1", "error");

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/validation-error"));
    }

    @Test
    void handleDataIntegrityViolationReturnsConflictStatus() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Unique constraint violation");

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void handleDataIntegrityViolationSetsErrorCode() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Unique constraint violation");

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_CONSTRAINT_VIOLATION.name());
    }

    @Test
    void handleDataIntegrityViolationIncludesGenericMessage() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Unique constraint violation");

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getDetail()).contains("constraint");
    }

    @Test
    void handleDataIntegrityViolationSetsErrorType() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Constraint violation");

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/constraint-violation"));
    }

    @Test
    void handleGenericReturnsInternalServerErrorStatus() {
        Exception ex = new Exception("Something went wrong");

        ProblemDetail problem = handler.handleGeneric(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleGenericSetsErrorCode() {
        Exception ex = new Exception("Something went wrong");

        ProblemDetail problem = handler.handleGeneric(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_INTERNAL_ERROR.name());
    }

    @Test
    void handleGenericExposesNoInternalExceptionDetail() {
        RuntimeException cause = new RuntimeException("Database connection failed");
        Exception ex = new Exception("Processing failed", cause);

        ProblemDetail problem = handler.handleGeneric(ex);

        String detail = problem.getDetail();
        assertThat(detail)
                .doesNotContain("Exception")
                .doesNotContain("RuntimeException")
                .doesNotContain("Database connection failed")
                .doesNotContain(ex.getClass().getName())
                .doesNotContain(cause.getClass().getName());
    }

    @Test
    void handleGenericDoesNotContainStackTrace() {
        Exception ex = new Exception("Error with at");

        ProblemDetail problem = handler.handleGeneric(ex);

        String detail = problem.getDetail();
        assertThat(detail).doesNotContain("at ");
    }

    @Test
    void handleGenericSetsErrorType() {
        Exception ex = new Exception("Error");

        ProblemDetail problem = handler.handleGeneric(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/internal-error"));
    }

    @Test
    void handleConstraintViolationReturnsBadRequestStatus() {
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        ConstraintViolationException ex = new ConstraintViolationException(violations);

        ProblemDetail problem = handler.handleConstraintViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void handleConstraintViolationSetsErrorCode() {
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        ConstraintViolationException ex = new ConstraintViolationException(violations);

        ProblemDetail problem = handler.handleConstraintViolation(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_VALIDATION_FAILED.name());
    }

    @Test
    void handleConstraintViolationSetsErrorType() {
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        ConstraintViolationException ex = new ConstraintViolationException(violations);

        ProblemDetail problem = handler.handleConstraintViolation(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/validation-error"));
    }

    @Test
    void handleMethodArgumentTypeMismatchReturnsBadRequestStatus() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException(
                        "badvalue", String.class, "param", null, null);

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void handleMethodArgumentTypeMismatchSetsErrorCode() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException(
                        "badvalue", String.class, "param", null, null);

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_INVALID_PARAMETER.name());
    }

    @Test
    void handleMethodArgumentTypeMismatchSetsErrorType() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException(
                        "badvalue", String.class, "param", null, null);

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/invalid-parameter"));
    }

    @Test
    void handleIllegalArgumentReturnsBadRequestStatus() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid sort field");

        ProblemDetail problem = handler.handleIllegalArgument(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void handleIllegalArgumentSetsErrorCode() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid sort field");

        ProblemDetail problem = handler.handleIllegalArgument(ex);

        assertThat(problem.getProperties())
                .containsEntry("code", ErrorCode.CERT_INVALID_PARAMETER.name());
    }

    @Test
    void handleIllegalArgumentSetsErrorType() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid sort field");

        ProblemDetail problem = handler.handleIllegalArgument(ex);

        assertThat(problem.getType())
                .isEqualTo(URI.create("https://errors.dcos.local/cert-api/invalid-parameter"));
    }

    private MethodArgumentNotValidException createValidationException(
            String fieldName, String message) {
        return createValidationExceptionWithMultipleErrors(Map.of(fieldName, message));
    }

    private MethodArgumentNotValidException createValidationExceptionWithMultipleErrors(
            Map<String, String> errors) {
        BindingResult bindingResult =
                new org.springframework.validation.MapBindingResult(Map.of(), "testObject");
        errors.forEach(
                (field, msg) -> bindingResult.addError(new FieldError("testObject", field, msg)));
        return new MethodArgumentNotValidException(null, bindingResult);
    }
}
