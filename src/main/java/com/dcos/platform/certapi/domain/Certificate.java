package com.dcos.platform.certapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Metadata for a single fictional certificate. Mapped to {@code certificates} in the schema managed
 * by Flyway; Hibernate validates this mapping against that schema at startup.
 *
 * <p>Deliberately carries no {@code equals}, {@code hashCode}, or {@code toString}: the identifier
 * is generated on persist, so field-based equality would break entity identity.
 */
@Entity
@Table(name = "certificates")
@Getter
@Setter
@NoArgsConstructor
public class Certificate {

    private static final int DEFAULT_RENEWAL_WINDOW_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String serialNumber;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String commonName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrchestrationStatus orchestrationStatus = OrchestrationStatus.PENDING;

    @Column(nullable = false)
    private String issuedBy;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int renewalWindowDays = DEFAULT_RENEWAL_WINDOW_DAYS;

    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    private RevocationReason revocationReason;

    private String revocationComment;

    @Column(nullable = false)
    private String requestedBy;

    private UUID correlationId;

    private String lastError;

    @Column(nullable = false)
    private int renewalCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Setter(AccessLevel.NONE)
    private Long version;
}
