package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable JPA Specifications for Certificate filtering. Each method returns null when its
 * argument is absent, so specifications can be combined with Specification.where().
 */
public class CertificateSpecifications {

    private CertificateSpecifications() {}

    /**
     * Filters certificates by status.
     *
     * @param status the certificate status to filter by, or null to skip this filter
     * @return a Specification matching the status, or null if status is null
     */
    public static Specification<Certificate> hasStatus(CertificateStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Filters certificates by type.
     *
     * @param type the certificate type to filter by, or null to skip this filter
     * @return a Specification matching the type, or null if type is null
     */
    public static Specification<Certificate> hasType(CertificateType type) {
        if (type == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    /**
     * Filters certificates by orchestration status.
     *
     * @param orchestrationStatus the orchestration status to filter by, or null to skip
     * @return a Specification matching the orchestration status, or null if orchestrationStatus is
     *     null
     */
    public static Specification<Certificate> hasOrchestrationStatus(
            OrchestrationStatus orchestrationStatus) {
        if (orchestrationStatus == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("orchestrationStatus"), orchestrationStatus);
    }

    /**
     * Filters certificates by issuing authority (exact match).
     *
     * @param issuedBy the issuing authority to filter by, or null to skip this filter
     * @return a Specification matching the issuing authority, or null if issuedBy is null
     */
    public static Specification<Certificate> hasIssuedBy(String issuedBy) {
        if (issuedBy == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("issuedBy"), issuedBy);
    }

    /**
     * Filters certificates by common name (case-insensitive substring match).
     *
     * @param commonName the common name substring to filter by, or null to skip this filter
     * @return a Specification matching the common name, or null if commonName is null
     */
    public static Specification<Certificate> hasCommonNameContaining(String commonName) {
        if (commonName == null) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("commonName")), "%" + commonName.toLowerCase() + "%");
    }

    /**
     * Filters certificates expiring before (or on) a given instant.
     *
     * @param expiringBefore the instant to compare against, or null to skip this filter
     * @return a Specification matching certificates that expire before the instant, or null if
     *     expiringBefore is null
     */
    public static Specification<Certificate> expiringBefore(Instant expiringBefore) {
        if (expiringBefore == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("expiresAt"), expiringBefore);
    }
}
