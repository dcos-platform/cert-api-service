package com.dcos.platform.certapi.exception;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CertificateNotFoundException.class)
    public ProblemDetail handleNotFound(CertificateNotFoundException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://errors.dcos.local/cert-api/not-found"));
        problem.setProperty("code", ErrorCode.CERT_NOT_FOUND.name());
        return problem;
    }

    @ExceptionHandler(CertificateStateException.class)
    public ProblemDetail handleStateException(CertificateStateException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://errors.dcos.local/cert-api/invalid-state"));
        problem.setProperty("code", ErrorCode.CERT_INVALID_STATE.name());
        return problem;
    }

    @ExceptionHandler(DuplicateCertificateException.class)
    public ProblemDetail handleDuplicate(DuplicateCertificateException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://errors.dcos.local/cert-api/duplicate-active"));
        problem.setProperty("code", ErrorCode.CERT_DUPLICATE_ACTIVE.name());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        FieldError::getDefaultMessage,
                                        (a, b) -> a));
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setType(URI.create("https://errors.dcos.local/cert-api/validation-error"));
        problem.setProperty("code", ErrorCode.CERT_VALIDATION_FAILED.name());
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        // Translate constraint violations; if it's a duplicate serial number or the partial unique
        // index on (subject, type, status), raise DuplicateCertificateException semantics.
        // For now, a generic constraint violation; the constraint name would be in the cause.
        log.warn("Data integrity violation", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "A data constraint was violated");
        problem.setType(URI.create("https://errors.dcos.local/cert-api/constraint-violation"));
        problem.setProperty("code", ErrorCode.CERT_CONSTRAINT_VIOLATION.name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create("https://errors.dcos.local/cert-api/internal-error"));
        problem.setProperty("code", ErrorCode.CERT_INTERNAL_ERROR.name());
        return problem;
    }
}
