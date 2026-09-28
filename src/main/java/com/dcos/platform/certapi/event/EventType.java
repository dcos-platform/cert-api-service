package com.dcos.platform.certapi.event;

public enum EventType {
    CREATED("cert.created"),
    RENEWED("cert.renewed"),
    REVOKED("cert.revoked"),
    EXPIRED("cert.expired");

    private final String routingKey;

    EventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
