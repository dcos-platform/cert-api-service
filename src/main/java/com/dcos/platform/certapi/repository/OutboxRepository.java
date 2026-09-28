package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.Outbox;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /**
     * Claims a bounded batch of pending outbox rows with pessimistic locking. Rows are locked for
     * update, ordered by creation time (oldest first), and limited by the provided limit. This
     * ensures that concurrent relay instances do not process the same row.
     *
     * @param limit maximum number of rows to claim
     * @return list of pending rows locked for update
     */
    @Query(
            value =
                    "SELECT o.* FROM dcos_certificates.outbox o "
                            + "WHERE o.state = 'PENDING' "
                            + "ORDER BY o.created_at ASC "
                            + "LIMIT :limit "
                            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Outbox> claimPending(@Param("limit") int limit);

    /**
     * Retrieves outbox rows by aggregate id, paged, ordered by creation time descending.
     *
     * @param aggregateId the certificate id
     * @param pageable pagination parameters
     * @return a page of outbox rows for the certificate
     */
    Page<Outbox> findByAggregateIdOrderByCreatedAtDesc(UUID aggregateId, Pageable pageable);
}
