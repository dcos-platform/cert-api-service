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

    /**
     * Counts outbox entries grouped by state for metrics collection.
     *
     * @return list of state combinations with their counts
     */
    @Query("select o.state as state, count(o) as count " + "from Outbox o group by o.state")
    List<OutboxStateCount> countGroupedByState();

    /**
     * Returns the epoch second of the oldest pending outbox entry, or null if none exist. The
     * returned value is stored as-is and is meant to be subtracted from the current epoch second at
     * read time to compute age, allowing the age to advance continuously between refreshes.
     *
     * <p>Note: outbox.created_at is TIMESTAMP in UTC (written by Hibernate as UTC), so this query
     * correctly computes epoch seconds from it.
     *
     * @return epoch second of oldest pending row, or null if no pending rows
     */
    @Query(
            value =
                    "SELECT CAST(EXTRACT(EPOCH FROM o.created_at) AS bigint) "
                            + "FROM dcos_certificates.outbox o "
                            + "WHERE o.state = 'PENDING' "
                            + "ORDER BY o.created_at ASC "
                            + "LIMIT 1",
            nativeQuery = true)
    Long getOldestPendingEpochSeconds();
}
