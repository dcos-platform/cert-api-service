package com.dcos.platform.certapi.domain;

/** The kind of certificate a record describes; constrained in the schema to these values. */
public enum CertificateType {
    TLS,
    CLIENT,
    CA,
    CODE_SIGNING
}
