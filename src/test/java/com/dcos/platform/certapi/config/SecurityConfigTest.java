package com.dcos.platform.certapi.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcos.platform.certapi.metrics.CertificateMetricsCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    @Autowired private MockMvc mvc;
    @Autowired private CertificateMetricsCollector metricsCollector;

    @Test
    @DisplayName("/actuator/prometheus returns 401 Unauthorized without authentication")
    void testPrometheusUnauthorized() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/actuator/prometheus returns 403 Forbidden for USER role")
    void testPrometheusUserForbidden() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(httpBasic("user", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/actuator/prometheus returns 200 OK for ADMIN role with metrics")
    void testPrometheusAdminAllowed() throws Exception {
        // Scheduled tasks are disabled in tests, so manually trigger the refresh
        metricsCollector.refresh();

        var result =
                mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "changeme")))
                        .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();

        assertTrue(status == 200, "ADMIN should get 200 OK, got " + status);

        // Verify free instrumentation is present
        assertTrue(
                body.contains("jvm_memory_used_bytes"),
                "Prometheus output should contain free JVM instrumentation (jvm_memory_used_bytes)");

        // Verify application tag is present
        assertTrue(
                body.contains("application=\"cert-api-service\""),
                "Prometheus output should contain application tag with cert-api-service value");
    }

    @Test
    @DisplayName("/actuator/health is permitted for all users")
    void testHealthPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("/actuator/info is permitted for all users")
    void testInfoPublic() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T4: cert.certificates.count has exactly 12 series (3 statuses × 4 types)")
    void testCertificateCountSeries() throws Exception {
        metricsCollector.refresh();

        var result =
                mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "changeme")))
                        .andReturn();

        String body = result.getResponse().getContentAsString();

        // Count exactly 12 cert_certificates_count lines (3 statuses × 4 types)
        long certCountLines =
                java.util.Arrays.stream(body.split("\n"))
                        .filter(line -> line.startsWith("cert_certificates_count{"))
                        .count();

        assertTrue(
                certCountLines == 12,
                "Should have exactly 12 cert_certificates_count lines, got " + certCountLines);
    }

    @Test
    @DisplayName(
            "T5: All 8 cert.* metric names in scrape before refresh, with correct unit suffixes")
    void testAllMetricNamesWithApplicationTag() throws Exception {
        // DO NOT call refresh() - check that all 8 metrics are pre-registered
        var result =
                mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "changeme")))
                        .andReturn();

        String body = result.getResponse().getContentAsString();

        // Verify all 8 cert metric names (with unit suffixes where applicable)
        String[] expectedMetrics = {
            "cert_certificates_count",
            "cert_certificates_orchestration_count",
            "cert_certificates_renewal_window_count",
            "cert_outbox_pending_count",
            "cert_outbox_failed_count",
            "cert_outbox_oldest_pending_age_seconds", // age gauge has _seconds suffix
            "cert_sweep_transitions_total", // counter has _total suffix
            "cert_completions_duplicates_suppressed_total" // counter has _total suffix
        };

        for (String metric : expectedMetrics) {
            assertTrue(body.contains(metric), "Prometheus output should contain metric " + metric);
        }

        // Verify application tag is on cert.* lines
        java.util.regex.Pattern certLineWithAppTag =
                java.util.regex.Pattern.compile(
                        "cert_\\w+\\{[^}]*application=\"cert-api-service\"");

        java.util.regex.Matcher matcher = certLineWithAppTag.matcher(body);
        assertTrue(
                matcher.find(),
                "At least one cert_* line should have application=\"cert-api-service\" tag");
    }

    @Test
    @DisplayName("T4b: Cardinality guard - no UUIDs in metric tag values")
    void testCardinalityGuardNoUuids() throws Exception {
        metricsCollector.refresh();

        var result =
                mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "changeme")))
                        .andReturn();

        String body = result.getResponse().getContentAsString();

        // UUID regex: 8-4-4-4-12 hex pattern
        java.util.regex.Pattern uuidPattern =
                java.util.regex.Pattern.compile(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        // Check that cert_* lines do not contain UUIDs in tag values
        for (String line : body.split("\n")) {
            if (line.startsWith("cert_")) {
                java.util.regex.Matcher matcher = uuidPattern.matcher(line);
                assertTrue(
                        !matcher.find(),
                        "cert_* metric line should not contain UUIDs in tags: " + line);
            }
        }
    }
}
