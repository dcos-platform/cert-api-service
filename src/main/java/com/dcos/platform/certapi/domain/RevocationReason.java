package com.dcos.platform.certapi.domain;

/** Why a certificate was revoked; recorded alongside the revocation timestamp. */
public enum RevocationReason {
    UNSPECIFIED,
    KEY_COMPROMISE,
    CA_COMPROMISE,
    AFFILIATION_CHANGED,
    SUPERSEDED,
    CESSATION_OF_OPERATION
}
