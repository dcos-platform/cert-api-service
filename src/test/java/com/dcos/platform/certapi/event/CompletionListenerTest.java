package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompletionListener unit tests (mocked repository)")
class CompletionListenerTest {

    @Mock private CertificateRepository certificateRepository;
    @Mock private CompletionInboxService inboxService;

    private CompletionListener listener;

    @BeforeEach
    void setUp() {
        listener = new CompletionListener(certificateRepository, inboxService);
    }

    @Test
    @DisplayName("consume: successful completion of pending certificate")
    void consumeSuccessfulCompletion() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "COMPLETED", 0, null);
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    @DisplayName("consume: failed completion with error message")
    void consumeFailedCompletion() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String errorMsg = "certificate validation failed";

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "FAILED", 0, errorMsg);
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(saved.getLastError()).isEqualTo(errorMsg);
    }

    @Test
    @DisplayName("consume: duplicate event (primary key collision)")
    void consumeDuplicateEvent() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(false);

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "COMPLETED", 0, null);
        listener.consume(event, null);

        verify(certificateRepository, never()).findById(any());
        verify(certificateRepository, never()).save(any());
    }

    @Test
    @DisplayName("consume: invalid certificate UUID")
    void consumeInvalidCertificateUuid() {
        String eventId = UUID.randomUUID().toString();
        String invalidUuid = "not-a-valid-uuid";

        CompletionEvent event = new CompletionEvent(eventId, invalidUuid, "COMPLETED", 0, null);
        listener.consume(event, null);

        verify(certificateRepository, never()).findById(any());
        verify(certificateRepository, never()).save(any());
        verify(inboxService, never()).recordProcessed(any(), any());
    }

    @Test
    @DisplayName("consume: certificate not found")
    void consumeCertificateNotFound() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.empty());

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "COMPLETED", 0, null);
        listener.consume(event, null);

        verify(certificateRepository, never()).save(any());
    }

    @Test
    @DisplayName("consume: unknown status (no-op)")
    void consumeUnknownStatus() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event = new CompletionEvent(eventId, certId.toString(), "unknown", 0, null);
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus())
                .isEqualTo(OrchestrationStatus.PENDING); // unchanged by unknown status
    }

    @Test
    @DisplayName("consume: preserves retry-suffixed event id in inbox")
    void consumePreservesRetryEventId() {
        UUID certId = UUID.randomUUID();
        String retryEventId = UUID.randomUUID().toString() + ":retry:1";

        Certificate cert = new Certificate();
        cert.setId(certId);

        when(inboxService.recordProcessed(retryEventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(retryEventId, certId.toString(), "COMPLETED", 1, null);
        listener.consume(event, null);

        verify(inboxService).recordProcessed(eq(retryEventId), eq(certId));
    }

    @Test
    @DisplayName("consume: case-insensitive status comparison (lowercase)")
    void consumeLowercaseStatus() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "completed", 0, null);
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
    }

    @Test
    @DisplayName("consume: case-insensitive status comparison (mixed case)")
    void consumeMixedCaseStatus() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "Failed", 0, "test error");
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(saved.getLastError()).isEqualTo("test error");
    }

    @Test
    @DisplayName("consume: null status (logs warning, no update)")
    void consumeNullStatus() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event = new CompletionEvent(eventId, certId.toString(), null, 0, null);
        listener.consume(event, null);

        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificateRepository).save(captor.capture());

        Certificate saved = captor.getValue();
        assertThat(saved.getOrchestrationStatus())
                .isEqualTo(OrchestrationStatus.PENDING); // unchanged by null status
    }
}
