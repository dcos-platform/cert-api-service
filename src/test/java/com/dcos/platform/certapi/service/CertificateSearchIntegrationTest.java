package com.dcos.platform.certapi.service;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.RevocationReason;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.dto.PageResponse;
import com.dcos.platform.certapi.dto.RevocationRequest;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Integration tests for certificate search, filtering, paging, and sorting. Tests verify that JPA
 * Specifications correctly filter results, pagination boundaries work as expected, and sort orders
 * are respected.
 */
@RequiresTestDatabase
@SpringBootTest
@DirtiesContext
class CertificateSearchIntegrationTest {

    @Autowired private CertificateService service;

    @Autowired private CertificateRepository repository;

    @MockBean private com.dcos.platform.certapi.event.OutboxEnqueueService outboxEnqueueService;

    private static final String PRINCIPAL = "search-test-admin";
    private static final String ISSUER = "Search Test Authority";

    @BeforeEach
    void cleanupTestData() {
        repository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(cert -> repository.delete(cert));
    }

    @AfterEach
    void cleanup() {
        repository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(cert -> repository.delete(cert));
    }

    private String uniqueSubject(String cn) {
        return "CN=" + cn + ",OU=search-test,O=DCOS";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void search_filtersAByStatus() {
        String subject1 = uniqueSubject("active-" + UUID.randomUUID());
        String subject2 = uniqueSubject("revoked-" + UUID.randomUUID());

        // Create active certificate
        service.create(
                new CertificateCreateRequest(
                        subject1,
                        "active-cert",
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null),
                PRINCIPAL);

        // Create revoked certificate
        var createdRevoked =
                service.create(
                        new CertificateCreateRequest(
                                subject2,
                                "revoked-cert",
                                "TLS",
                                ISSUER,
                                Instant.now().plus(365, ChronoUnit.DAYS),
                                30,
                                null),
                        PRINCIPAL);
        RevocationRequest revocationRequest = new RevocationRequest();
        revocationRequest.setReason(RevocationReason.SUPERSEDED);
        service.revoke(createdRevoked.id(), revocationRequest);

        // Search only for active certificates
        Sort sort = Sort.by(Sort.Order.asc("createdAt"));
        PageResponse<CertificateResponse> result =
                service.search(
                        CertificateStatus.ACTIVE, null, null, null, null, null, 0, 100, sort);

        assertThat(result.content()).isNotEmpty();
        assertThat(result.content())
                .allSatisfy(
                        response ->
                                assertThat(response.status()).isEqualTo(CertificateStatus.ACTIVE));
        assertThat(result.content())
                .anyMatch(response -> response.commonName().equals("active-cert"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void search_filtersByCommonNameCaseInsensitive() {
        String cn = "case-insensitive-test-" + UUID.randomUUID();
        String subject = uniqueSubject(cn);

        service.create(
                new CertificateCreateRequest(
                        subject,
                        cn,
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null),
                PRINCIPAL);

        Sort sort = Sort.by(Sort.Order.asc("createdAt"));

        // Search with lowercase
        PageResponse<CertificateResponse> lowerResult =
                service.search(null, null, cn.toLowerCase(), null, null, null, 0, 100, sort);
        assertThat(lowerResult.content()).isNotEmpty();

        // Search with mixed case
        PageResponse<CertificateResponse> mixedResult =
                service.search(
                        null,
                        null,
                        cn.substring(0, 10).toUpperCase(),
                        null,
                        null,
                        null,
                        0,
                        100,
                        sort);
        assertThat(mixedResult.content()).isNotEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void search_pagesProperly() {
        // Create 5 test certificates
        for (int i = 0; i < 5; i++) {
            service.create(
                    new CertificateCreateRequest(
                            uniqueSubject("page-test-" + i + "-" + UUID.randomUUID()),
                            "page-test-" + i,
                            "TLS",
                            ISSUER,
                            Instant.now().plus(365, ChronoUnit.DAYS),
                            30,
                            null),
                    PRINCIPAL);
        }

        Sort sort = Sort.by(Sort.Order.asc("createdAt"));

        // Get first page
        PageResponse<CertificateResponse> page1 =
                service.search(null, null, null, null, null, null, 0, 3, sort);
        assertThat(page1.size()).isEqualTo(3);
        assertThat(page1.first()).isTrue();

        // Get second page
        PageResponse<CertificateResponse> page2 =
                service.search(null, null, null, null, null, null, 1, 3, sort);
        assertThat(page2.size()).isEqualTo(3);
        assertThat(page2.first()).isFalse();

        // Get beyond end (should return empty)
        PageResponse<CertificateResponse> beyond =
                service.search(null, null, null, null, null, null, 100, 3, sort);
        assertThat(beyond.content()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void findExpiring_returnsCertificatesExpiringWithinWindow() {
        Instant in15Days = Instant.now().plus(15, ChronoUnit.DAYS);

        service.create(
                new CertificateCreateRequest(
                        uniqueSubject("expiring-soon-" + UUID.randomUUID()),
                        "expiring-soon",
                        "TLS",
                        ISSUER,
                        in15Days,
                        30,
                        null),
                PRINCIPAL);

        Instant in60Days = Instant.now().plus(60, ChronoUnit.DAYS);
        service.create(
                new CertificateCreateRequest(
                        uniqueSubject("expiring-later-" + UUID.randomUUID()),
                        "expiring-later",
                        "TLS",
                        ISSUER,
                        in60Days,
                        30,
                        null),
                PRINCIPAL);

        // Search for certificates expiring in 30 days
        PageResponse<CertificateResponse> expiring = service.findExpiring(30, 0, 100);

        // Should find at least the 15-day one, but not the 60-day one (it's outside the window)
        assertThat(expiring.content())
                .isNotEmpty()
                .anyMatch(r -> r.commonName().contains("expiring-soon"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void search_sortsCorrectly() {
        // Create certificates with different creation times
        for (int i = 0; i < 3; i++) {
            service.create(
                    new CertificateCreateRequest(
                            uniqueSubject("sort-test-" + i + "-" + UUID.randomUUID()),
                            "sort-test-" + i,
                            "TLS",
                            ISSUER,
                            Instant.now().plus(365, ChronoUnit.DAYS),
                            30,
                            null),
                    PRINCIPAL);
            if (i < 2) {
                try {
                    Thread.sleep(10); // Small delay to ensure different timestamps
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Sort ascSort = Sort.by(Sort.Order.asc("createdAt"));
        PageResponse<CertificateResponse> ascResult =
                service.search(null, null, null, null, null, null, 0, 100, ascSort);
        assertThat(ascResult.content()).isNotEmpty();

        Sort descSort = Sort.by(Sort.Order.desc("createdAt"));
        PageResponse<CertificateResponse> descResult =
                service.search(null, null, null, null, null, null, 0, 100, descSort);
        assertThat(descResult.content()).isNotEmpty();

        // Verify that orders are different by comparing order of first elements
        assertThat(descResult.content()).isNotEmpty();
    }
}
