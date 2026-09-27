package com.dcos.platform.certapi.repository;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for CertificateSpecifications helper methods. Verify that each specification correctly
 * returns null for absent filters and builds appropriate JPA predicates when filters are present.
 */
class CertificateSpecificationsTest {

    @Test
    void hasStatus_returnsNullWhenStatusIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.hasStatus(null);
        assertThat(spec).isNull();
    }

    @Test
    void hasStatus_returnsSpecificationWhenStatusIsPresent() {
        Specification<Certificate> spec =
                CertificateSpecifications.hasStatus(CertificateStatus.ACTIVE);
        assertThat(spec).isNotNull();
    }

    @Test
    void hasType_returnsNullWhenTypeIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.hasType(null);
        assertThat(spec).isNull();
    }

    @Test
    void hasType_returnsSpecificationWhenTypeIsPresent() {
        Specification<Certificate> spec = CertificateSpecifications.hasType(CertificateType.TLS);
        assertThat(spec).isNotNull();
    }

    @Test
    void hasOrchestrationStatus_returnsNullWhenStatusIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.hasOrchestrationStatus(null);
        assertThat(spec).isNull();
    }

    @Test
    void hasOrchestrationStatus_returnsSpecificationWhenStatusIsPresent() {
        Specification<Certificate> spec =
                CertificateSpecifications.hasOrchestrationStatus(OrchestrationStatus.PENDING);
        assertThat(spec).isNotNull();
    }

    @Test
    void hasIssuedBy_returnsNullWhenIssuedByIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.hasIssuedBy(null);
        assertThat(spec).isNull();
    }

    @Test
    void hasIssuedBy_returnsSpecificationWhenIssuedByIsPresent() {
        Specification<Certificate> spec = CertificateSpecifications.hasIssuedBy("Internal CA");
        assertThat(spec).isNotNull();
    }

    @Test
    void hasCommonNameContaining_returnsNullWhenCommonNameIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.hasCommonNameContaining(null);
        assertThat(spec).isNull();
    }

    @Test
    void hasCommonNameContaining_returnsSpecificationWhenCommonNameIsPresent() {
        Specification<Certificate> spec =
                CertificateSpecifications.hasCommonNameContaining("example");
        assertThat(spec).isNotNull();
    }

    @Test
    void expiringBefore_returnsNullWhenExpiringBeforeIsNull() {
        Specification<Certificate> spec = CertificateSpecifications.expiringBefore(null);
        assertThat(spec).isNull();
    }

    @Test
    void expiringBefore_returnsSpecificationWhenExpiringBeforeIsPresent() {
        Instant cutoff = Instant.now().plus(30, ChronoUnit.DAYS);
        Specification<Certificate> spec = CertificateSpecifications.expiringBefore(cutoff);
        assertThat(spec).isNotNull();
    }
}
