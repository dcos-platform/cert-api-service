package com.dcos.platform.certapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * Inbox record for completion event idempotency. The event_id is stored as-is from the
 * orchestrator, including any retry-suffix markers ({@code originalId:retry:N}), which is why it is
 * varchar(255) rather than UUID. A primary-key collision on reinsertion signals a duplicate
 * completion.
 *
 * <p>Implements Persistable to force Spring Data JPA to call persist() instead of merge(), ensuring
 * duplicate event ids throw DataIntegrityViolationException rather than silently updating the
 * existing row.
 */
@Entity
@Table(name = "processed_completions")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedCompletion implements Persistable<String> {

    @Id
    @Column(length = 255, nullable = false)
    private String eventId;

    @Column(nullable = false)
    private UUID certificateId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    @Transient private boolean isNew = true;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    void postPersist() {
        isNew = false;
    }
}
