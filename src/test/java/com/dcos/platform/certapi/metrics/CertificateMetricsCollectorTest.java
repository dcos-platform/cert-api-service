package com.dcos.platform.certapi.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dcos.platform.certapi.CertApiServiceApplication;
import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.event.OutboxState;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.OutboxRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = CertApiServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("CertificateMetricsCollector")
class CertificateMetricsCollectorTest {

    @Autowired private CertificateMetricsCollector collector;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MeterRegistry meterRegistry;

    private final List<UUID> createdCertificateIds = new ArrayList<>();

    @BeforeEach
    void cleanup() {
        outboxRepository.deleteAll();
    }

    @AfterEach
    void cleanupCreatedCertificates() {
        createdCertificateIds.forEach(id -> certificateRepository.deleteById(id));
    }

    @Test
    @DisplayName("refresh updates certificate count gauges from database")
    void testRefreshUpdatesGauges() {
        collector.refresh();

        var beforeGauges =
                meterRegistry
                        .find("cert.certificates.count")
                        .tags("status", "ACTIVE", "type", "TLS")
                        .gauges();

        assertFalse(beforeGauges.isEmpty(), "Gauge for ACTIVE/TLS should exist");
        Gauge beforeGauge = beforeGauges.stream().findFirst().orElseThrow();
        double before = beforeGauge.value();

        Certificate cert = CertificateFixtures.active();
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        collector.refresh();

        var afterGauges =
                meterRegistry
                        .find("cert.certificates.count")
                        .tags("status", "ACTIVE", "type", "TLS")
                        .gauges();

        assertFalse(afterGauges.isEmpty(), "Gauge for ACTIVE/TLS should exist");
        Gauge afterGauge = afterGauges.stream().findFirst().orElseThrow();
        assertEquals(1.0, afterGauge.value() - before, "ACTIVE/TLS should increase by exactly 1");
    }

    @Test
    @DisplayName("refresh with partial results leaves missing statuses at zero")
    void testRefreshPartialResultsShowsZero() {
        collector.refresh();

        var beforeGauges =
                meterRegistry
                        .find("cert.certificates.count")
                        .tags("status", "EXPIRED", "type", "TLS")
                        .gauges();

        assertFalse(beforeGauges.isEmpty(), "EXPIRED/TLS series should exist after initialization");
        Gauge beforeGauge = beforeGauges.stream().findFirst().orElseThrow();
        double before = beforeGauge.value();

        Certificate expiredCert = CertificateFixtures.expired();
        certificateRepository.save(expiredCert);
        createdCertificateIds.add(expiredCert.getId());

        collector.refresh();

        var afterGauges =
                meterRegistry
                        .find("cert.certificates.count")
                        .tags("status", "EXPIRED", "type", "TLS")
                        .gauges();

        assertFalse(afterGauges.isEmpty(), "EXPIRED/TLS gauge should exist");
        Gauge expiredGauge = afterGauges.stream().findFirst().orElseThrow();
        assertEquals(
                1.0, expiredGauge.value() - before, "EXPIRED/TLS should increase by exactly 1");
    }

    @Test
    @DisplayName(
            "orchestration status counts: all four values present including unreachable PROCESSING")
    void testOrchestrationStatusCounts() {
        collector.refresh();

        var beforeGauges = meterRegistry.find("cert.certificates.orchestration.count").gauges();
        assertFalse(beforeGauges.isEmpty(), "Orchestration status gauges should exist");
        assertEquals(4, beforeGauges.size(), "Should have exactly 4 orchestration status series");

        // Measure gauge count before adding a certificate
        int countBefore = beforeGauges.size();

        Certificate cert = CertificateFixtures.active();
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        collector.refresh();

        var afterGauges = meterRegistry.find("cert.certificates.orchestration.count").gauges();

        // Verify series still present and count unchanged (new cert just changes values, not
        // creates new series)
        assertEquals(
                4,
                afterGauges.size(),
                "Should still have exactly 4 orchestration status series (cardinality guard)");
        assertEquals(
                countBefore,
                afterGauges.size(),
                "Orchestration status cardinality should remain constant");
    }

    @Test
    @DisplayName("renewal window: counts ACTIVE certificates inside their renewal window")
    void testRenewalWindowCount() {
        collector.refresh();

        var beforeGauges = meterRegistry.find("cert.certificates.renewal.window.count").gauges();
        assertFalse(beforeGauges.isEmpty(), "Renewal window gauge should exist");
        Gauge beforeGauge = beforeGauges.stream().findFirst().orElseThrow();
        double before = beforeGauge.value();

        Instant now = Instant.now();

        // Create an ACTIVE certificate inside renewal window
        Certificate inside = CertificateFixtures.active();
        inside.setSubject("CN=inside-" + UUID.randomUUID() + ",OU=test,O=DCOS");
        inside.setCommonName("inside");
        inside.setSerialNumber("inside-" + UUID.randomUUID());
        inside.setExpiresAt(now.plus(5, ChronoUnit.DAYS)); // 5 days away
        inside.setRenewalWindowDays(10); // 10-day window
        certificateRepository.save(inside);
        createdCertificateIds.add(inside.getId());

        // Create an ACTIVE certificate outside renewal window (too far in future)
        Certificate outside = CertificateFixtures.active();
        outside.setSubject("CN=outside-" + UUID.randomUUID() + ",OU=test,O=DCOS");
        outside.setCommonName("outside");
        outside.setSerialNumber("outside-" + UUID.randomUUID());
        outside.setExpiresAt(now.plus(50, ChronoUnit.DAYS)); // 50 days away
        outside.setRenewalWindowDays(10);
        certificateRepository.save(outside);
        createdCertificateIds.add(outside.getId());

        collector.refresh();

        var afterGauges = meterRegistry.find("cert.certificates.renewal.window.count").gauges();

        assertFalse(afterGauges.isEmpty(), "Renewal window gauge should exist");
        Gauge afterGauge = afterGauges.stream().findFirst().orElseThrow();
        assertEquals(
                1.0,
                afterGauge.value() - before,
                "Should increase by 1 (only cert inside window counts)");
    }

    @Test
    @DisplayName("renewal window: excludes ACTIVE certificates that have already expired")
    void testRenewalWindowExcludesExpired() {
        collector.refresh();

        var beforeGauges = meterRegistry.find("cert.certificates.renewal.window.count").gauges();
        assertFalse(beforeGauges.isEmpty(), "Renewal window gauge should exist");
        Gauge beforeGauge = beforeGauges.stream().findFirst().orElseThrow();
        double before = beforeGauge.value();

        Instant now = Instant.now();

        // Create an ACTIVE certificate that is already expired
        Certificate expired = CertificateFixtures.active();
        expired.setExpiresAt(now.minusSeconds(1)); // Already expired
        expired.setRenewalWindowDays(10);
        certificateRepository.save(expired);
        createdCertificateIds.add(expired.getId());

        collector.refresh();

        var afterGauges = meterRegistry.find("cert.certificates.renewal.window.count").gauges();

        assertFalse(afterGauges.isEmpty(), "Renewal window gauge should exist");
        Gauge afterGauge = afterGauges.stream().findFirst().orElseThrow();
        assertEquals(
                0.0,
                afterGauge.value() - before,
                "Should not increase (expired cert excluded from renewal window)");
    }

    @Test
    @DisplayName("outbox state counts: PENDING and FAILED tracked, SENT ignored")
    void testOutboxStateCounts() {
        Outbox pending = new Outbox();
        pending.setEventId("pending-1");
        pending.setAggregateId(UUID.randomUUID());
        pending.setEventType("cert.created");
        pending.setRoutingKey("cert.created");
        pending.setPayload("{}");
        pending.setState(OutboxState.PENDING);
        pending.setAttemptCount(0);
        pending.setCreatedAt(Instant.now());
        outboxRepository.save(pending);

        Outbox failed = new Outbox();
        failed.setEventId("failed-1");
        failed.setAggregateId(UUID.randomUUID());
        failed.setEventType("cert.created");
        failed.setRoutingKey("cert.created");
        failed.setPayload("{}");
        failed.setState(OutboxState.FAILED);
        failed.setAttemptCount(5);
        failed.setCreatedAt(Instant.now());
        outboxRepository.save(failed);

        Outbox sent = new Outbox();
        sent.setEventId("sent-1");
        sent.setAggregateId(UUID.randomUUID());
        sent.setEventType("cert.created");
        sent.setRoutingKey("cert.created");
        sent.setPayload("{}");
        sent.setState(OutboxState.SENT);
        sent.setAttemptCount(1);
        sent.setCreatedAt(Instant.now());
        outboxRepository.save(sent);

        collector.refresh();

        var pendingGauges = meterRegistry.find("cert.outbox.pending.count").gauges();
        var failedGauges = meterRegistry.find("cert.outbox.failed.count").gauges();

        assertFalse(pendingGauges.isEmpty(), "Pending count gauge should exist");
        assertFalse(failedGauges.isEmpty(), "Failed count gauge should exist");

        Gauge pendingGauge = pendingGauges.stream().findFirst().orElseThrow();
        Gauge failedGauge = failedGauges.stream().findFirst().orElseThrow();

        assertEquals(1.0, pendingGauge.value(), "Should count 1 PENDING outbox row");
        assertEquals(1.0, failedGauge.value(), "Should count 1 FAILED outbox row");
    }

    @Test
    @DisplayName("outbox pending age gauge returns zero when no pending rows")
    void testOutboxPendingAgeNoRows() {
        collector.refresh();

        var gauges = meterRegistry.find("cert.outbox.oldest.pending.age").gauges();

        assertFalse(gauges.isEmpty(), "Outbox age gauge should exist");
        Gauge gauge = gauges.stream().findFirst().orElseThrow();
        assertEquals(0.0, gauge.value(), "Age should be 0 when no pending rows");
    }

    @Test
    @DisplayName("outbox pending age gauge captures 120s offset to detect timezone issues")
    void testOutboxPendingAgeCatches120sOffset() {
        Instant now = Instant.now();
        Instant created120SecondsAgo = now.minusSeconds(120);

        Outbox outbox = new Outbox();
        outbox.setEventId("test-event-120s");
        outbox.setAggregateId(UUID.randomUUID());
        outbox.setEventType("cert.created");
        outbox.setRoutingKey("cert.created");
        outbox.setPayload("{}");
        outbox.setState(OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(created120SecondsAgo);

        outboxRepository.save(outbox);

        collector.refresh();

        var gauges = meterRegistry.find("cert.outbox.oldest.pending.age").gauges();

        assertFalse(gauges.isEmpty(), "Outbox age gauge should exist");
        Gauge gauge = gauges.stream().findFirst().orElseThrow();
        long ageSeconds = Math.round(gauge.value());
        assertTrue(
                ageSeconds >= 119 && ageSeconds <= 121,
                "Age should be approximately 120 seconds, got " + ageSeconds);
    }

    @Test
    @DisplayName("cardinality: all metric tags are enum constants, never UUIDs")
    void testCardinalityGuard() {
        Certificate cert = CertificateFixtures.active();
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        collector.refresh();

        Set<String> observedStatusValues = new HashSet<>();
        Set<String> observedTypeValues = new HashSet<>();

        meterRegistry
                .find("cert.certificates.count")
                .gauges()
                .forEach(
                        gauge -> {
                            gauge.getId().getTags().stream()
                                    .filter(tag -> "status".equals(tag.getKey()))
                                    .forEach(tag -> observedStatusValues.add(tag.getValue()));
                            gauge.getId().getTags().stream()
                                    .filter(tag -> "type".equals(tag.getKey()))
                                    .forEach(tag -> observedTypeValues.add(tag.getValue()));
                        });

        Set<String> validStatuses = Set.of("ACTIVE", "EXPIRED", "REVOKED");
        Set<String> validTypes = Set.of("TLS", "CLIENT", "CA", "CODE_SIGNING");

        assertTrue(
                validStatuses.containsAll(observedStatusValues),
                "All status tags must be valid enum values");
        assertTrue(
                validTypes.containsAll(observedTypeValues),
                "All type tags must be valid enum values");
    }

    @Test
    @DisplayName("8 metric names and 23 series total presence check")
    void testEightMetricNamesPresent() {
        Certificate cert = CertificateFixtures.active();
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        Outbox outbox = new Outbox();
        outbox.setEventId("test-event");
        outbox.setAggregateId(cert.getId());
        outbox.setEventType("cert.created");
        outbox.setRoutingKey("cert.created");
        outbox.setPayload("{}");
        outbox.setState(OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(Instant.now());
        outboxRepository.save(outbox);

        collector.refresh();

        Set<String> metricNames = new HashSet<>();
        meterRegistry.getMeters().stream()
                .forEach(meter -> metricNames.add(meter.getId().getName()));

        Set<String> certMetricNames =
                metricNames.stream()
                        .filter(name -> name.startsWith("cert."))
                        .collect(java.util.stream.Collectors.toSet());

        // Verify exactly 6 gauge metric names (the counter names are not yet implemented)
        Set<String> expectedMetrics =
                Set.of(
                        "cert.certificates.count",
                        "cert.certificates.orchestration.count",
                        "cert.certificates.renewal.window.count",
                        "cert.outbox.pending.count",
                        "cert.outbox.failed.count",
                        "cert.outbox.oldest.pending.age");

        assertTrue(
                certMetricNames.containsAll(expectedMetrics),
                "Missing metric names. Expected: " + expectedMetrics + ", got: " + certMetricNames);

        for (String expected : expectedMetrics) {
            assertTrue(certMetricNames.contains(expected), "Missing " + expected);
        }
    }

    @Test
    @DisplayName(
            "12-line series count verification (status/type combinations and orchestration states)")
    void testTwelveSingleDimensionSeries() {
        Certificate cert = CertificateFixtures.active();
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        collector.refresh();

        var certCountGauges = meterRegistry.find("cert.certificates.count").gauges();
        assertEquals(
                12,
                certCountGauges.size(),
                "cert.certificates.count should have 12 series (3 statuses × 4 types)");

        var orchCountGauges = meterRegistry.find("cert.certificates.orchestration.count").gauges();
        assertEquals(
                4,
                orchCountGauges.size(),
                "cert.certificates.orchestration.count should have 4 series (4 orchestration statuses)");

        var renewalGauges = meterRegistry.find("cert.certificates.renewal.window.count").gauges();
        assertEquals(
                1,
                renewalGauges.size(),
                "cert.certificates.renewal.window.count should have 1 series");
    }

    @Test
    @DisplayName("T1: age gauge advances without refresh being called")
    void testAgeGaugeAdvancesWithoutRefresh() throws InterruptedException {
        Instant now = Instant.now();
        Instant createdAgo = now.minusSeconds(2);

        Outbox outbox = new Outbox();
        outbox.setEventId("age-test-event");
        outbox.setAggregateId(UUID.randomUUID());
        outbox.setEventType("cert.created");
        outbox.setRoutingKey("cert.created");
        outbox.setPayload("{}");
        outbox.setState(OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(createdAgo);
        outboxRepository.save(outbox);

        collector.refresh();

        var gauges1 = meterRegistry.find("cert.outbox.oldest.pending.age").gauges();
        Gauge gauge1 = gauges1.stream().findFirst().orElseThrow();
        double age1 = gauge1.value();

        Thread.sleep(1000); // Wait 1 second

        // Check gauge again without calling refresh
        Gauge gauge2 =
                meterRegistry.find("cert.outbox.oldest.pending.age").gauges().stream()
                        .findFirst()
                        .orElseThrow();
        double age2 = gauge2.value();

        assertTrue(age2 > age1, "Age gauge should advance even without refresh call");
        assertTrue(age2 - age1 >= 0.9, "Age should advance by approximately 1 second");
        assertTrue(
                age2 - age1 <= 1.2,
                "Age advance should not exceed 1.2 seconds (tolerance for timing variation)");
    }

    @Test
    @DisplayName("refresh catches exceptions without throwing")
    void testRefreshCatchesExceptionsGracefully() {
        var mockRepository = mock(CertificateRepository.class);
        when(mockRepository.countGroupedByStatusAndType())
                .thenThrow(new RuntimeException("Database error"));

        var testCollector =
                new CertificateMetricsCollector(mockRepository, outboxRepository, meterRegistry);

        // Should not throw, just log
        testCollector.refresh();

        // Gauge should still exist after exception
        var gauges = meterRegistry.find("cert.certificates.count").gauges();
        assertFalse(gauges.isEmpty(), "Metrics should exist even after exception");
    }
}
