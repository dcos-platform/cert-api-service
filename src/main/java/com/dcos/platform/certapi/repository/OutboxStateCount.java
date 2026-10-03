package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.event.OutboxState;

/** Projection for outbox counts grouped by state. */
public interface OutboxStateCount {
    OutboxState getState();

    long getCount();
}
