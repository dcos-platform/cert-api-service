package com.dcos.platform.certapi.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcos.platform.certapi.CertApiServiceApplication;
import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.dto.RenewalRequest;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that the partial unique index on (subject, type) WHERE status = 'ACTIVE' is properly
 * enforced, preventing duplicate ACTIVE certificates for the same subject and type. Must use
 * MockMvc because the constraint fires when the transaction commits as the controller returns.
 */
@SpringBootTest(classes = CertApiServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("RenewalCollision")
class RenewalCollisionTest {

    @Autowired private MockMvc mvc;
    @Autowired private CertificateRepository repository;
    @Autowired private ObjectMapper objectMapper;

    private final List<UUID> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> repository.deleteById(id));
    }

    @Test
    @DisplayName("duplicate ACTIVE certificates for same subject+type violate partial unique index")
    void testRenewalCollisionConstraint() throws Exception {
        // Create first ACTIVE certificate
        Certificate first = CertificateFixtures.active();
        repository.save(first);

        String subject = first.getSubject();
        UUID firstId = first.getId();
        createdIds.add(firstId);

        // Create second EXPIRED certificate with same subject and type
        Certificate second = CertificateFixtures.expired();
        second.setSubject(subject);
        second.setCommonName(first.getCommonName());
        second.setType(first.getType());
        repository.save(second);

        UUID secondId = second.getId();
        createdIds.add(secondId);

        // Attempt to renew the second certificate to future expiry.
        // This would transition it to ACTIVE with the same subject/type, violating the partial
        // unique index constraint on (subject, type) WHERE status = 'ACTIVE'
        RenewalRequest renewalRequest = new RenewalRequest();
        renewalRequest.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        renewalRequest.setRenewalWindowDays(30);

        String requestBody = objectMapper.writeValueAsString(renewalRequest);

        mvc.perform(
                        post("/api/v1/certificates/" + secondId + "/renew")
                                .contentType("application/json")
                                .content(requestBody)
                                .with(httpBasic("admin", "changeme")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_CONSTRAINT_VIOLATION"));

        // Verify both certificates are still in their original state
        Certificate persistedFirst = repository.findById(firstId).orElse(null);
        assertNotNull(persistedFirst, "First certificate should still exist");
        assertEquals(
                CertificateStatus.ACTIVE,
                persistedFirst.getStatus(),
                "First certificate status should be unchanged");

        Certificate persistedSecond = repository.findById(secondId).orElse(null);
        assertNotNull(persistedSecond, "Second certificate should still exist");
        assertEquals(
                CertificateStatus.EXPIRED,
                persistedSecond.getStatus(),
                "Second certificate status should remain EXPIRED after failed renewal");
    }

    @Test
    @DisplayName("Item 23: revoke ACTIVE, then renew EXPIRED succeeds (positive control)")
    void testRevokeAndRenewSucceeds() throws Exception {
        // Create ACTIVE certificate
        Certificate active = CertificateFixtures.active();
        repository.save(active);

        String subject = active.getSubject();
        UUID activeId = active.getId();
        createdIds.add(activeId);

        // Create EXPIRED certificate with same subject and type
        Certificate expired = CertificateFixtures.expired();
        expired.setSubject(subject);
        expired.setCommonName(active.getCommonName());
        expired.setType(active.getType());
        repository.save(expired);

        UUID expiredId = expired.getId();
        createdIds.add(expiredId);

        // Step 1: Revoke the ACTIVE certificate
        mvc.perform(
                        post("/api/v1/certificates/" + activeId + "/revoke")
                                .contentType("application/json")
                                .content("{\"reason\": \"SUPERSEDED\"}")
                                .with(httpBasic("admin", "changeme")))
                .andExpect(status().isOk());

        // Step 2: Renew the EXPIRED certificate to ACTIVE (should succeed because ACTIVE is now
        // REVOKED)
        RenewalRequest renewalRequest = new RenewalRequest();
        renewalRequest.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        renewalRequest.setRenewalWindowDays(30);

        String requestBody = objectMapper.writeValueAsString(renewalRequest);

        mvc.perform(
                        post("/api/v1/certificates/" + expiredId + "/renew")
                                .contentType("application/json")
                                .content(requestBody)
                                .with(httpBasic("admin", "changeme")))
                .andExpect(status().isOk());

        // Verify: first is REVOKED, second is now ACTIVE
        Certificate persistedActive = repository.findById(activeId).orElse(null);
        assertNotNull(persistedActive, "First certificate should still exist");
        assertEquals(
                CertificateStatus.REVOKED,
                persistedActive.getStatus(),
                "First certificate should be REVOKED");

        Certificate persistedExpired = repository.findById(expiredId).orElse(null);
        assertNotNull(persistedExpired, "Second certificate should still exist");
        assertEquals(
                CertificateStatus.ACTIVE,
                persistedExpired.getStatus(),
                "Second certificate should now be ACTIVE after successful renewal");
    }
}
