package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.OrchestrationStatus;

/** Projection for certificate counts grouped by orchestration status. */
public interface OrchestrationStatusCount {
    OrchestrationStatus getOrchestrationStatus();

    long getCount();
}
