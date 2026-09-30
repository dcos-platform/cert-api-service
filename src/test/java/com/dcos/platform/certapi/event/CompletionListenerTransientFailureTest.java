package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class CompletionListenerTransientFailureTest {

    @Autowired private CompletionListener listener;

    @MockBean private CertificateRepository certificateRepository;

    @MockBean private CompletionInboxService inboxService;

    @Test
    void transientFailureThenRecoveryRetries() {
        UUID certificateId = UUID.randomUUID();
        Certificate cert = new Certificate();
        cert.setId(certificateId);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);

        AtomicInteger attemptCount = new AtomicInteger(0);
        when(inboxService.recordProcessed(anyString(), eq(certificateId))).thenReturn(true);
        when(certificateRepository.findById(certificateId))
                .thenAnswer(
                        invocation -> {
                            attemptCount.incrementAndGet();
                            if (attemptCount.get() < 3) {
                                throw new RuntimeException("Transient database failure");
                            }
                            return Optional.of(cert);
                        });
        when(certificateRepository.save(any())).thenReturn(cert);

        CompletionEvent event =
                new CompletionEvent(
                        UUID.randomUUID().toString(),
                        certificateId.toString(),
                        "completed",
                        0,
                        null);

        // With retry configured via @EnableRetry and @Retryable on consume(), this should
        // eventually succeed after transient failures. The proxy created by Spring AOP will retry.
        assertThatCode(() -> listener.consume(event, null)).doesNotThrowAnyException();

        // Verify repository was called exactly 3 times (initial attempt + 2 retries)
        verify(certificateRepository, times(3)).findById(certificateId);
    }

    @Test
    void persistentFailureExhaustsRetries() {
        UUID certificateId = UUID.randomUUID();

        when(inboxService.recordProcessed(anyString(), eq(certificateId))).thenReturn(true);
        when(certificateRepository.findById(certificateId))
                .thenThrow(new RuntimeException("Persistent database failure"));

        CompletionEvent event =
                new CompletionEvent(
                        UUID.randomUUID().toString(),
                        certificateId.toString(),
                        "completed",
                        0,
                        null);

        // Should eventually fail after exhausting all retry attempts
        assertThatThrownBy(() -> listener.consume(event, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Persistent database failure");

        // Verify repository was called exactly 3 times (initial + 2 retries)
        verify(certificateRepository, times(3)).findById(certificateId);
    }
}
