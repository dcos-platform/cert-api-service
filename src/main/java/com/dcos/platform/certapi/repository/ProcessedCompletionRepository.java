package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.ProcessedCompletion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Repository for completion idempotency records. */
@Repository
public interface ProcessedCompletionRepository extends JpaRepository<ProcessedCompletion, String> {

    /**
     * Inserts a processed-completion record unless one already exists for the given event id. Uses
     * {@code INSERT ... ON CONFLICT DO NOTHING} so a duplicate event id never throws; the caller
     * distinguishes a new record from a duplicate by the affected row count. This avoids Spring's
     * JPA exception translation marking the current transaction rollback-only, which a caught
     * {@code DataIntegrityViolationException} from a normal entity save cannot undo.
     *
     * @param eventId the unique event identifier (including any retry suffix)
     * @param certificateId the certificate UUID this event applies to
     * @return 1 if a new row was inserted, 0 if the event id already existed (duplicate)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    "INSERT INTO dcos_certificates.processed_completions (event_id, certificate_id) "
                            + "VALUES (:eventId, :certificateId) ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId, @Param("certificateId") UUID certificateId);
}
