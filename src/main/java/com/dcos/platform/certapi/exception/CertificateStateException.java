package com.dcos.platform.certapi.exception;

import java.util.UUID;

public class CertificateStateException extends RuntimeException {

    public CertificateStateException(UUID id, String message) {
        super("Certificate " + id + ": " + message);
    }
}
