-- ============================================================
-- V6: Fix idempotency constraint + add PROJECT permissions
-- ============================================================

-- C) Drop the permanent unique constraint on (buyer_user_id, idempotency_key_hash).
--    The business rule is time-windowed (10 min) uniqueness, enforced in the
--    application layer. A permanent DB constraint prevents key reuse after the
--    window expires, which conflicts with the spec.
ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_buyer_user_id_idempotency_key_hash_key;

-- Add a non-unique index so look-ups by (buyer_user_id, hash) remain fast.
CREATE INDEX IF NOT EXISTS idx_orders_idem_key
    ON orders (buyer_user_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

-- B) Add PROJECT resource permissions (idempotent).
INSERT INTO permissions (id, resource_type, operation)
VALUES
    (gen_random_uuid(), 'PROJECT', 'READ'),
    (gen_random_uuid(), 'PROJECT', 'WRITE')
ON CONFLICT (resource_type, operation) DO NOTHING;

-- Assign PROJECT:READ and PROJECT:WRITE to ADMIN role.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
       JOIN permissions p ON p.resource_type = 'PROJECT'
WHERE  r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- Assign PROJECT:READ to MEMBER role (members can read projects).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
       JOIN permissions p ON p.resource_type = 'PROJECT' AND p.operation = 'READ'
WHERE  r.name = 'MEMBER'
ON CONFLICT DO NOTHING;

-- Assign PROJECT:READ to TECHNICIAN role.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
       JOIN permissions p ON p.resource_type = 'PROJECT' AND p.operation = 'READ'
WHERE  r.name = 'TECHNICIAN'
ON CONFLICT DO NOTHING;
