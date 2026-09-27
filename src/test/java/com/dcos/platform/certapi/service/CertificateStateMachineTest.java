package com.dcos.platform.certapi.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dcos.platform.certapi.domain.CertificateStatus;
import org.junit.jupiter.api.Test;

class CertificateStateMachineTest {

    @Test
    void allowsRenewFromActive() {
        assertDoesNotThrow(
                () -> CertificateStateMachine.validateRenewTransition(CertificateStatus.ACTIVE));
    }

    @Test
    void allowsRenewFromExpired() {
        assertDoesNotThrow(
                () -> CertificateStateMachine.validateRenewTransition(CertificateStatus.EXPIRED));
    }

    @Test
    void rejectsRenewFromRevoked() {
        assertThrows(
                IllegalStateException.class,
                () -> CertificateStateMachine.validateRenewTransition(CertificateStatus.REVOKED));
    }

    @Test
    void allowsRevokeFromActive() {
        assertDoesNotThrow(
                () -> CertificateStateMachine.validateRevokeTransition(CertificateStatus.ACTIVE));
    }

    @Test
    void allowsRevokeFromExpired() {
        assertDoesNotThrow(
                () -> CertificateStateMachine.validateRevokeTransition(CertificateStatus.EXPIRED));
    }

    @Test
    void rejectsRevokeFromRevoked() {
        assertThrows(
                IllegalStateException.class,
                () -> CertificateStateMachine.validateRevokeTransition(CertificateStatus.REVOKED));
    }

    @Test
    void transitionActiveThatIsExpiredToExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.ACTIVE, true);
        assertEquals(CertificateStatus.EXPIRED, result);
    }

    @Test
    void keepActiveWhenNotExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.ACTIVE, false);
        assertEquals(CertificateStatus.ACTIVE, result);
    }

    @Test
    void keepExpiredWhenExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.EXPIRED, true);
        assertEquals(CertificateStatus.EXPIRED, result);
    }

    @Test
    void keepRevokedTerminal() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.REVOKED, true);
        assertEquals(CertificateStatus.REVOKED, result);
    }

    @Test
    void keepRevokedEvenWhenNotExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.REVOKED, false);
        assertEquals(CertificateStatus.REVOKED, result);
    }

    @Test
    void keepExpiredWhenNotExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.EXPIRED, false);
        assertEquals(CertificateStatus.EXPIRED, result);
    }

    @Test
    void keepRevokedWhenNotExpired() {
        CertificateStatus result =
                CertificateStateMachine.transitionOnExpiry(CertificateStatus.REVOKED, false);
        assertEquals(CertificateStatus.REVOKED, result);
    }
}
