-- ============================================================
-- V3 – Add ADMIN:WRITE permission and assign to ADMIN role
-- ============================================================

-- Insert the new permission (idempotent: skip if already present)
INSERT INTO permissions (id, resource_type, operation)
VALUES (gen_random_uuid(), 'ADMIN', 'WRITE')
ON CONFLICT (resource_type, operation) DO NOTHING;

-- Assign it to the ADMIN role using sub-selects (avoids hard-coded UUIDs)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
       JOIN permissions p ON p.resource_type = 'ADMIN' AND p.operation = 'WRITE'
WHERE  r.name = 'ADMIN'
ON CONFLICT DO NOTHING;
