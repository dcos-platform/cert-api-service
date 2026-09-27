package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled component that sweeps active certificates and transitions those past their expiry to
 * EXPIRED status. This runs periodically to ensure the synchronous status axis reflects the current
 * time. The sweep iterates in batches to avoid holding locks on large result sets.
 */
@Component
public class CertificateExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(CertificateExpirySweep.class);
    private static final int BATCH_SIZE = 100;

    private final CertificateRepository repository;

    public CertificateExpirySweep(CertificateRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${cert-api.expiry-sweep-interval:300000}")
    @Transactional
    public void sweep() {
        log.debug("Starting certificate expiry sweep");

        Instant now = Instant.now();
        int totalProcessed = 0;
        boolean hasMore = true;

        while (hasMore) {
            Pageable batch = PageRequest.of(0, BATCH_SIZE);
            List<Certificate> activeAndExpired =
                    repository.findByStatusAndExpiresAtBefore(CertificateStatus.ACTIVE, now, batch);

            if (activeAndExpired.isEmpty()) {
                hasMore = false;
            } else {
                for (Certificate cert : activeAndExpired) {
                    cert.setStatus(CertificateStatus.EXPIRED);
                    repository.save(cert);
                    totalProcessed++;
                }
            }
        }

        if (totalProcessed > 0) {
            log.info("Expiry sweep completed: {} certificates moved to EXPIRED", totalProcessed);
        } else {
            log.debug("Expiry sweep completed: no certificates to expire");
        }
    }
}
