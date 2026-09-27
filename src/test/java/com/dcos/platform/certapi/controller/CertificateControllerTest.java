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
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.RevocationReason;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.dto.PageResponse;
import com.dcos.platform.certapi.dto.RenewalRequest;
import com.dcos.platform.certapi.dto.RevocationRequest;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CertificateController.class)
@Import(SecurityConfig.class)
class CertificateControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private CertificateService service;

    private CertificateCreateRequest createRequest;
    private RenewalRequest renewalRequest;
    private RevocationRequest revocationRequest;

    @BeforeEach
    void setUp() {
        Instant tomorrow = Instant.now().plus(365, ChronoUnit.DAYS);
        Instant nextYear = Instant.now().plus(730, ChronoUnit.DAYS);
        createRequest =
                new CertificateCreateRequest(
                        "CN=test.example.com,OU=test,O=DCOS",
                        "test.example.com",
                        "TLS",
                        "Internal CA",
                        tomorrow,
                        30,
                        null);

        renewalRequest = new RenewalRequest();
        renewalRequest.setExpiresAt(nextYear);
        renewalRequest.setRenewalWindowDays(30);

        revocationRequest = new RevocationRequest();
        revocationRequest.setReason(RevocationReason.SUPERSEDED);
        revocationRequest.setComment("Renewed");
    }

    private CertificateResponse buildResponse(UUID id) {
        Certificate cert = new Certificate();
        cert.setId(id);
        cert.setSerialNumber("DCOS-2026-ABCDEF123456");
        cert.setSubject("CN=test.example.com,OU=test,O=DCOS");
        cert.setCommonName("test.example.com");
        cert.setType(CertificateType.TLS);
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        cert.setIssuedBy("Internal CA");
        cert.setRequestedBy("admin");
        return CertificateResponse.from(cert);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void createCertificate_shouldReturn201WithLocationHeader() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(CertificateCreateRequest.class), eq("admin")))
                .thenReturn(buildResponse(id));

        mockMvc.perform(
                        post("/api/v1/certificates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Location", "/api/v1/certificates/" + id))
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
                                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createCertificate_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(
                        post("/api/v1/certificates")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCertificate_shouldReturn400_forInvalidRequest() throws Exception {
        CertificateCreateRequest invalid =
                new CertificateCreateRequest(
                        "",
                        null,
                        "INVALID_TYPE",
                        "",
                        Instant.now().minus(1, ChronoUnit.DAYS),
                        null,
                        null);

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
        CertificateResponse response = buildResponse(id);
        PageResponse<CertificateResponse> pageResponse =
                new PageResponse<>(List.of(response), 0, 1, 1, 1, true, true);
        when(service.search(
                        any(), any(), any(), any(), any(), any(), eq(0), eq(20), any(Sort.class)))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/certificates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));
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
        when(service.renew(eq(id), any(RenewalRequest.class))).thenReturn(buildResponse(id));

        mockMvc.perform(
                        post("/api/v1/certificates/{id}/renew", id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(renewalRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void renewCertificate_shouldReturn400_forInvalidRequest() throws Exception {
        UUID id = UUID.randomUUID();

        RenewalRequest invalid = new RenewalRequest();
        invalid.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(
                        post("/api/v1/certificates/{id}/renew", id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeCertificate_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        Certificate revokedCert = new Certificate();
        revokedCert.setId(id);
        revokedCert.setSerialNumber("DCOS-2026-ABCDEF123456");
        revokedCert.setSubject("CN=test.example.com,OU=test,O=DCOS");
        revokedCert.setCommonName("test.example.com");
        revokedCert.setType(CertificateType.TLS);
        revokedCert.setStatus(CertificateStatus.REVOKED);
        revokedCert.setIssuedAt(Instant.now());
        revokedCert.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        revokedCert.setIssuedBy("Internal CA");
        revokedCert.setRequestedBy("admin");
        revokedCert.setRevokedAt(Instant.now());
        revokedCert.setRevocationReason(RevocationReason.SUPERSEDED);
        when(service.revoke(eq(id), any(RevocationRequest.class)))
                .thenReturn(CertificateResponse.from(revokedCert));

        mockMvc.perform(
                        post("/api/v1/certificates/{id}/revoke", id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(revocationRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeCertificate_shouldReturn400_forMissingReason() throws Exception {
        UUID id = UUID.randomUUID();

        RevocationRequest invalid = new RevocationRequest();
        invalid.setReason(null);

        mockMvc.perform(
                        post("/api/v1/certificates/{id}/revoke", id)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }
}
