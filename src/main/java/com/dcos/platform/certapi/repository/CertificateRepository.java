package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, UUID> {

    List<Certificate> findByStatus(CertificateStatus status);

    List<Certificate> findBySubjectContainingIgnoreCase(String subject);
}
