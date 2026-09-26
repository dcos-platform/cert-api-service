package com.dcos.platform.certapi.support;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.domain.RevocationReason;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared test data factory: builds valid, unsaved {@link Certificate} instances in the common
 * lifecycle states. Each call yields a unique subject and serial number, so instances can be
 * persisted side by side. Tests use this rather than duplicating construction logic, and later
 * stories extend it as new types appear.
 */
public final class CertificateFixtures {

    public static final String ISSUER = "DCOS Test Authority";
    public static final String REQUESTED_BY = "test-admin";
    public static final int RENEWAL_WINDOW_DAYS = 30;

    private static final int SERIAL_HEX_LENGTH = 12;

    private CertificateFixtures() {}

    /** An active certificate, issued yesterday and valid for a year. */
    public static Certificate active() {
        Instant now = Instant.now();
        return base(
                CertificateStatus.ACTIVE,
                now.minus(1, ChronoUnit.DAYS),
                now.plus(365, ChronoUnit.DAYS));
    }

    /** A certificate past its expiry date and marked expired. */
    public static Certificate expired() {
        Instant now = Instant.now();
        return base(
                CertificateStatus.EXPIRED,
                now.minus(400, ChronoUnit.DAYS),
                now.minus(35, ChronoUnit.DAYS));
    }

    /** A revoked certificate, carrying the revocation timestamp and reason the schema requires. */
    public static Certificate revoked() {
        Certificate cert = active();
        cert.setStatus(CertificateStatus.REVOKED);
        cert.setRevokedAt(Instant.now());
        cert.setRevocationReason(RevocationReason.KEY_COMPROMISE);
        cert.setRevocationComment("revoked by fixture");
        return cert;
    }

    /** An active certificate that expires within its renewal window. */
    public static Certificate inRenewalWindow() {
        Instant now = Instant.now();
        return base(
                CertificateStatus.ACTIVE,
                now.minus(355, ChronoUnit.DAYS),
                now.plus(RENEWAL_WINDOW_DAYS / 3, ChronoUnit.DAYS));
    }

    private static Certificate base(CertificateStatus status, Instant issuedAt, Instant expiresAt) {
        String commonName = "fixture-" + UUID.randomUUID();
        Certificate cert = new Certificate();
        cert.setSerialNumber(serialNumber());
        cert.setSubject("CN=" + commonName + ",OU=test,O=DCOS");
        cert.setCommonName(commonName);
        cert.setType(CertificateType.TLS);
        cert.setStatus(status);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        cert.setIssuedBy(ISSUER);
        cert.setIssuedAt(issuedAt);
        cert.setExpiresAt(expiresAt);
        cert.setRenewalWindowDays(RENEWAL_WINDOW_DAYS);
        cert.setRequestedBy(REQUESTED_BY);
        return cert;
    }

    private static String serialNumber() {
        String hex =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, SERIAL_HEX_LENGTH)
                        .toUpperCase(Locale.ROOT);
        return "DCOS-TEST-" + hex;
    }
}
