package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.repository.ProcessedCompletionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Manages completion event inbox (processed_completions table) for duplicate detection. */
@Component
@RequiredArgsConstructor
public class CompletionInboxService {

    private final ProcessedCompletionRepository processedCompletionRepository;

    /**
     * Attempts to record a completion event as processed. Backed by an {@code INSERT ... ON
     * CONFLICT DO NOTHING} statement, so a duplicate event id never throws — it simply inserts zero
     * rows. This keeps a duplicate delivery a clean, exception-free no-op instead of relying on
     * catching a constraint-violation exception, which marks the enclosing transaction
     * rollback-only regardless of where it is caught.
     *
     * @param eventId the unique event identifier (including any retry suffix)
     * @param certificateId the certificate UUID this event applies to
     * @return true if the inbox record was newly inserted, false if it already existed (duplicate)
     */
    @Transactional
    public boolean recordProcessed(String eventId, UUID certificateId) {
        return processedCompletionRepository.insertIfAbsent(eventId, certificateId) > 0;
    }
}
