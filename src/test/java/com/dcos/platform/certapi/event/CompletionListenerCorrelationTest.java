package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompletionListener correlation behavior tests")
class CompletionListenerCorrelationTest {

    @Mock private CertificateRepository certificateRepository;
    @Mock private CompletionInboxService inboxService;

    private CompletionListener listener;

    @BeforeEach
    void setUp() {
        listener = new CompletionListener(certificateRepository, inboxService);
        MDC.clear();
    }

    @Test
    @DisplayName("consume: uses inbound correlation header")
    void usesInboundCorrelationHeader() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String correlationHeaderValue = "inbound-correlation-id";
        AtomicReference<String> capturedMdc = new AtomicReference<>();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        // Capture MDC during handler execution
        setupHandlerCapture(capturedMdc);

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "completed", 0, null);
        listener.consume(event, correlationHeaderValue);

        assertThat(capturedMdc.get()).isEqualTo(correlationHeaderValue);
        assertThat(MDC.get(LoggingContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("consume: falls back to certificate correlation id when header absent")
    void fallsBackToCertificateCorrelationId() {
        UUID certId = UUID.randomUUID();
        UUID certCorrelationId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        AtomicReference<String> capturedMdc = new AtomicReference<>();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        cert.setCorrelationId(certCorrelationId);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        setupHandlerCapture(capturedMdc);

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "completed", 0, null);
        listener.consume(event, null);

        assertThat(capturedMdc.get()).isEqualTo(certCorrelationId.toString());
        assertThat(MDC.get(LoggingContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("consume: generates correlation id when header and certificate both absent")
    void generatesCorrelationIdWhenBothAbsent() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        AtomicReference<String> capturedMdc = new AtomicReference<>();

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        setupHandlerCapture(capturedMdc);

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "completed", 0, null);
        listener.consume(event, null);

        assertThat(capturedMdc.get()).isNotNull();
        assertThat(capturedMdc.get()).matches("^[a-f0-9-]{36}$"); // UUID format
        assertThat(MDC.get(LoggingContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("consume: MDC cleared after handling")
    void mdcClearedAfterHandling() {
        UUID certId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String correlationHeaderValue = "test-correlation";

        Certificate cert = new Certificate();
        cert.setId(certId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        when(inboxService.recordProcessed(eventId, certId)).thenReturn(true);
        when(certificateRepository.findById(certId)).thenReturn(Optional.of(cert));

        CompletionEvent event =
                new CompletionEvent(eventId, certId.toString(), "completed", 0, null);
        listener.consume(event, correlationHeaderValue);

        assertThat(MDC.get(LoggingContext.CORRELATION_ID)).isNull();
    }

    private void setupHandlerCapture(AtomicReference<String> capturedMdc) {
        when(certificateRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            capturedMdc.set(MDC.get(LoggingContext.CORRELATION_ID));
                            return invocation.getArgument(0);
                        });
    }
}
