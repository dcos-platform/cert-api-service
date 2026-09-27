package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.CertificateStatus;

/**
 * State machine for the synchronous certificate status axis (ACTIVE, EXPIRED, REVOKED). This is
 * pure logic with no framework or repository dependencies, making it trivially unit-testable and
 * reusable by any service layer client.
 *
 * <p>Transitions follow the design document's state table. The orchestration status axis (PENDING,
 * PROCESSING, COMPLETED, FAILED) is handled separately by completions and sweeps.
 */
public class CertificateStateMachine {

    private CertificateStateMachine() {
        // Static utility; prevent instantiation
    }

    /**
     * Validates that a renewal is permitted from the current status.
     *
     * @param currentStatus the certificate's current status
     * @throws IllegalStateException if renewal is not permitted from this status
     */
    public static void validateRenewTransition(CertificateStatus currentStatus) {
        switch (currentStatus) {
            case ACTIVE:
            case EXPIRED:
                // Renewal is allowed from both states
                break;
            case REVOKED:
                throw new IllegalStateException("Cannot renew a revoked certificate");
            default:
                throw new IllegalStateException("Unknown certificate status: " + currentStatus);
        }
    }

    /**
     * Validates that a revocation is permitted from the current status.
     *
     * @param currentStatus the certificate's current status
     * @throws IllegalStateException if revocation is not permitted from this status
     */
    public static void validateRevokeTransition(CertificateStatus currentStatus) {
        switch (currentStatus) {
            case ACTIVE:
            case EXPIRED:
                // Revocation is allowed from both states
                break;
            case REVOKED:
                // REVOKED is terminal; once revoked, no further transitions are allowed
                throw new IllegalStateException("Certificate is already revoked");
            default:
                throw new IllegalStateException("Unknown certificate status: " + currentStatus);
        }
    }

    /**
     * Determines the status that results from an expiry sweep on the current status. This is
     * typically invoked by a scheduled sweep when the current time exceeds the certificate's
     * expiration instant.
     *
     * @param currentStatus the certificate's current status
     * @param isExpired true if the current time has passed the certificate's expiresAt instant
     * @return the new status, or the current status if no transition occurs
     * @throws IllegalStateException if the state machine receives an unknown status
     */
    public static CertificateStatus transitionOnExpiry(
            CertificateStatus currentStatus, boolean isExpired) {
        if (!isExpired) {
            return currentStatus;
        }

        switch (currentStatus) {
            case ACTIVE:
                // ACTIVE cert past expiry moves to EXPIRED
                return CertificateStatus.EXPIRED;
            case EXPIRED:
                // Already expired; no change
                return CertificateStatus.EXPIRED;
            case REVOKED:
                // REVOKED is terminal; expiry does not override it
                return CertificateStatus.REVOKED;
            default:
                throw new IllegalStateException("Unknown certificate status: " + currentStatus);
        }
    }
}
