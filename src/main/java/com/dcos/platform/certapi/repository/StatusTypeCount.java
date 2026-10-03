package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;

/** Projection for certificate counts grouped by status and type. */
public interface StatusTypeCount {
    CertificateStatus getStatus();

    CertificateType getType();

    long getCount();
}
