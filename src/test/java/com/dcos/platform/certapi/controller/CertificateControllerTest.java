package com.dcos.platform.certapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dcos.platform.certapi.config.SecurityConfig;
import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.dto.CertificateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.service.CertificateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CertificateController.class)
@Import(SecurityConfig.class)
class CertificateControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private CertificateService service;

    private CertificateResponse sampleResponse;
    private CertificateRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = new CertificateRequest();
        sampleRequest.setSubject("CN=test.example.com");
        sampleRequest.setType("TLS");
        sampleRequest.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        sampleRequest.setIssuedBy("Internal CA");

        sampleResponse = new CertificateResponse();
    }

    private CertificateResponse buildResponse(UUID id) {
        Certificate cert = new Certificate();
        cert.setId(id);
        cert.setSubject("CN=test.example.com");
        cert.setType("TLS");
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        cert.setIssuedBy("Internal CA");
        return CertificateResponse.from(cert);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCertificate_shouldReturn201() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(CertificateRequest.class))).thenReturn(buildResponse(id));

        mockMvc.perform(
                        post("/api/v1/certificates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void createCertificate_shouldReturn403_forUser() throws Exception {
        mockMvc.perform(
                        post("/api/v1/certificates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCertificate_shouldReturn400_forInvalidRequest() throws Exception {
        CertificateRequest invalid = new CertificateRequest();
        invalid.setType("INVALID_TYPE");

        mockMvc.perform(
                        post("/api/v1/certificates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void getAllCertificates_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAll()).thenReturn(List.of(buildResponse(id)));

        mockMvc.perform(get("/api/v1/certificates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getCertificateById_shouldReturn200_whenFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenReturn(buildResponse(id));

        mockMvc.perform(get("/api/v1/certificates/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getCertificateById_shouldReturn404_whenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new CertificateNotFoundException(id));

        mockMvc.perform(get("/api/v1/certificates/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void renewCertificate_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.renew(eq(id), any(CertificateRequest.class))).thenReturn(buildResponse(id));

        mockMvc.perform(
                        put("/api/v1/certificates/{id}/renew", id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeCertificate_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        Certificate revokedCert = new Certificate();
        revokedCert.setId(id);
        revokedCert.setSubject("CN=test.example.com");
        revokedCert.setType("TLS");
        revokedCert.setStatus(CertificateStatus.REVOKED);
        revokedCert.setIssuedAt(Instant.now());
        revokedCert.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        revokedCert.setIssuedBy("Internal CA");
        when(service.revoke(id)).thenReturn(CertificateResponse.from(revokedCert));

        mockMvc.perform(patch("/api/v1/certificates/{id}/revoke", id).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }
}
