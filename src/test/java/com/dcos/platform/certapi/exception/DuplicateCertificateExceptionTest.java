package com.dcos.platform.certapi.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DuplicateCertificateExceptionTest {

    @Test
    void constructorSetsSubjectAndType() {
        String subject = "CN=test,OU=platform,O=DCOS";
        String type = "TLS";

        DuplicateCertificateException ex = new DuplicateCertificateException(subject, type);

        assertThat(ex.getMessage()).contains(subject).contains(type);
    }

    @Test
    void messageFormatsSubjectAndType() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=api,OU=services,O=DCOS", "CLIENT");

        assertThat(ex.getMessage())
                .contains("CN=api,OU=services,O=DCOS")
                .contains("CLIENT")
                .contains("already exists");
    }

    @Test
    void isAnException() {
        DuplicateCertificateException ex =
                new DuplicateCertificateException("CN=test,O=DCOS", "TLS");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
