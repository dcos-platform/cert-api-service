package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Data access for Certificate entities. Extends JpaRepository for CRUD and finder method
 * generation, and JpaSpecificationExecutor for flexible filtering via Specifications.
 */
@Repository
public interface CertificateRepository
        extends JpaRepository<Certificate, UUID>, JpaSpecificationExecutor<Certificate> {

    List<Certificate> findByStatus(CertificateStatus status);

    List<Certificate> findBySubjectContainingIgnoreCase(String subject);

    /**
     * Checks whether a certificate with the given serial number exists.
     *
     * @param serialNumber the serial number to look up
     * @return true if a certificate with this serial number exists
     */
    boolean existsBySerialNumber(String serialNumber);
}
