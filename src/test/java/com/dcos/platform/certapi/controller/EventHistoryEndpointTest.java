package com.dcos.platform.certapi.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.event.OutboxState;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.OutboxRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice tests for the event history endpoint. Verifies correct response structure, paging, and
 * not-found handling.
 */
@RequiresTestDatabase
@SpringBootTest
class EventHistoryEndpointTest {

    @Autowired private WebApplicationContext context;

    @Autowired private CertificateRepository certificateRepository;

    @Autowired private OutboxRepository outboxRepository;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
        certificateRepository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(certificateRepository::delete);
    }

    @Test
    @WithMockUser
    @Transactional
    void eventHistoryReturnsCorrectShape() throws Exception {
        var cert = certificateRepository.save(CertificateFixtures.active());
        UUID certId = cert.getId();

        var row1 = new Outbox();
        row1.setEventId(UUID.randomUUID().toString());
        row1.setAggregateId(certId);
        row1.setEventType("CREATED");
        row1.setRoutingKey("cert.created");
        row1.setPayload("{}");
        row1.setState(OutboxState.SENT);
        row1.setAttemptCount(0);
        row1.setCreatedAt(Instant.now());
        outboxRepository.save(row1);

        mockMvc.perform(
                        get("/api/v1/certificates/" + certId + "/events")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventId").value(row1.getEventId()))
                .andExpect(jsonPath("$.content[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.content[0].state").value("SENT"))
                .andExpect(jsonPath("$.content[0].attempts").value(0));
    }

    @Test
    @WithMockUser
    void eventHistoryNotFoundForUnknownCertificate() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(
                        get("/api/v1/certificates/" + unknownId + "/events")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @Transactional
    void eventHistoryIsPaged() throws Exception {
        var cert = certificateRepository.save(CertificateFixtures.active());
        UUID certId = cert.getId();

        // Create 5 events
        for (int i = 0; i < 5; i++) {
            var row = new Outbox();
            row.setEventId(UUID.randomUUID().toString());
            row.setAggregateId(certId);
            row.setEventType("CREATED");
            row.setRoutingKey("cert.created");
            row.setPayload("{}");
            row.setState(OutboxState.PENDING);
            row.setAttemptCount(0);
            row.setCreatedAt(Instant.now().plusSeconds(i));
            outboxRepository.save(row);
        }

        // First page with size 2
        mockMvc.perform(
                        get("/api/v1/certificates/" + certId + "/events")
                                .param("page", "0")
                                .param("size", "2")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @WithMockUser
    @Transactional
    void eventHistoryOrderedByCreatedAtDescending() throws Exception {
        var cert = certificateRepository.save(CertificateFixtures.active());
        UUID certId = cert.getId();

        long baseTime = System.currentTimeMillis() / 1000;
        for (int i = 0; i < 3; i++) {
            var row = new Outbox();
            row.setEventId(UUID.randomUUID().toString());
            row.setAggregateId(certId);
            row.setEventType("CREATED");
            row.setRoutingKey("cert.created");
            row.setPayload("{}");
            row.setState(OutboxState.PENDING);
            row.setAttemptCount(0);
            row.setCreatedAt(Instant.ofEpochSecond(baseTime + i));
            outboxRepository.save(row);
        }

        mockMvc.perform(
                        get("/api/v1/certificates/" + certId + "/events")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }
}
