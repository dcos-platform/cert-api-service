-- Five fictional, domain-neutral certificates with fixed identifiers for tests and demos.
-- Validity is relative to now() so the data stays useful over time; service-echo sits inside
-- its renewal window. ON CONFLICT makes the insert safe to re-run.
INSERT INTO dcos_certificates.certificates
    (id, serial_number, subject, common_name, type, status, orchestration_status,
     issued_by, issued_at, expires_at, renewal_window_days, requested_by)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'DCOS-2026-A1B2C3D4E5F6',
     'CN=service-alpha,OU=platform,O=DCOS', 'service-alpha', 'TLS', 'ACTIVE', 'COMPLETED',
     'DCOS Demo Authority', now() - interval '30 days', now() + interval '335 days', 30, 'seed'),
    ('22222222-2222-4222-8222-222222222222', 'DCOS-2026-B2C3D4E5F6A1',
     'CN=service-bravo,OU=platform,O=DCOS', 'service-bravo', 'CLIENT', 'ACTIVE', 'COMPLETED',
     'DCOS Demo Authority', now() - interval '60 days', now() + interval '305 days', 30, 'seed'),
    ('33333333-3333-4333-8333-333333333333', 'DCOS-2026-C3D4E5F6A1B2',
     'CN=dcos-root,OU=platform,O=DCOS', 'dcos-root', 'CA', 'ACTIVE', 'COMPLETED',
     'DCOS Demo Authority', now() - interval '365 days', now() + interval '1460 days', 90, 'seed'),
    ('44444444-4444-4444-8444-444444444444', 'DCOS-2026-D4E5F6A1B2C3',
     'CN=build-signer,OU=release,O=DCOS', 'build-signer', 'CODE_SIGNING', 'ACTIVE', 'COMPLETED',
     'DCOS Demo Authority', now() - interval '90 days', now() + interval '275 days', 30, 'seed'),
    ('55555555-5555-4555-8555-555555555555', 'DCOS-2026-E5F6A1B2C3D4',
     'CN=service-echo,OU=platform,O=DCOS', 'service-echo', 'TLS', 'ACTIVE', 'COMPLETED',
     'DCOS Demo Authority', now() - interval '350 days', now() + interval '15 days', 30, 'seed')
ON CONFLICT (id) DO NOTHING;
