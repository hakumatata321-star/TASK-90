-- ============================================================
-- V2 – Seed Data for RICMS
-- Default admin account (password should be rotated on first login)
-- ============================================================

DO $$
DECLARE
  v_admin_role_id     UUID;
  v_member_role_id    UUID;
  v_tech_role_id      UUID;
  v_admin_user_id     UUID;

  p_order_read        UUID;
  p_order_write       UUID;
  p_user_read         UUID;
  p_user_write        UUID;
  p_rbac_write        UUID;
  p_admin_read        UUID;
  p_wo_read           UUID;
  p_wo_write          UUID;
  p_outcome_read      UUID;
  p_outcome_write     UUID;
  p_interaction_write UUID;
  p_auth_self_write   UUID;
BEGIN

  -- --------------------------------------------------------
  -- Permissions
  -- --------------------------------------------------------
  INSERT INTO permissions (id, resource_type, operation) VALUES
    (gen_random_uuid(), 'ORDER',            'READ'),
    (gen_random_uuid(), 'ORDER',            'WRITE'),
    (gen_random_uuid(), 'USER',             'READ'),
    (gen_random_uuid(), 'USER',             'WRITE'),
    (gen_random_uuid(), 'RBAC_USER_ROLES',  'WRITE'),
    (gen_random_uuid(), 'ADMIN',            'READ'),
    (gen_random_uuid(), 'WORK_ORDER',       'READ'),
    (gen_random_uuid(), 'WORK_ORDER',       'WRITE'),
    (gen_random_uuid(), 'OUTCOME',          'READ'),
    (gen_random_uuid(), 'OUTCOME',          'WRITE'),
    (gen_random_uuid(), 'INTERACTION',      'WRITE'),
    (gen_random_uuid(), 'AUTH_USER_SELF',   'WRITE');

  SELECT id INTO p_order_read        FROM permissions WHERE resource_type='ORDER'           AND operation='READ';
  SELECT id INTO p_order_write       FROM permissions WHERE resource_type='ORDER'           AND operation='WRITE';
  SELECT id INTO p_user_read         FROM permissions WHERE resource_type='USER'            AND operation='READ';
  SELECT id INTO p_user_write        FROM permissions WHERE resource_type='USER'            AND operation='WRITE';
  SELECT id INTO p_rbac_write        FROM permissions WHERE resource_type='RBAC_USER_ROLES' AND operation='WRITE';
  SELECT id INTO p_admin_read        FROM permissions WHERE resource_type='ADMIN'           AND operation='READ';
  SELECT id INTO p_wo_read           FROM permissions WHERE resource_type='WORK_ORDER'      AND operation='READ';
  SELECT id INTO p_wo_write          FROM permissions WHERE resource_type='WORK_ORDER'      AND operation='WRITE';
  SELECT id INTO p_outcome_read      FROM permissions WHERE resource_type='OUTCOME'         AND operation='READ';
  SELECT id INTO p_outcome_write     FROM permissions WHERE resource_type='OUTCOME'         AND operation='WRITE';
  SELECT id INTO p_interaction_write FROM permissions WHERE resource_type='INTERACTION'     AND operation='WRITE';
  SELECT id INTO p_auth_self_write   FROM permissions WHERE resource_type='AUTH_USER_SELF'  AND operation='WRITE';

  -- --------------------------------------------------------
  -- Roles
  -- --------------------------------------------------------
  v_admin_role_id  := gen_random_uuid();
  v_member_role_id := gen_random_uuid();
  v_tech_role_id   := gen_random_uuid();

  INSERT INTO roles (id, name, description) VALUES
    (v_admin_role_id,  'ADMIN',      'System administrator with full access'),
    (v_member_role_id, 'MEMBER',     'Regular membership user'),
    (v_tech_role_id,   'TECHNICIAN', 'Service technician for work orders');

  -- Admin gets all permissions
  INSERT INTO role_permissions (role_id, permission_id) VALUES
    (v_admin_role_id, p_order_read),
    (v_admin_role_id, p_order_write),
    (v_admin_role_id, p_user_read),
    (v_admin_role_id, p_user_write),
    (v_admin_role_id, p_rbac_write),
    (v_admin_role_id, p_admin_read),
    (v_admin_role_id, p_wo_read),
    (v_admin_role_id, p_wo_write),
    (v_admin_role_id, p_outcome_read),
    (v_admin_role_id, p_outcome_write),
    (v_admin_role_id, p_interaction_write),
    (v_admin_role_id, p_auth_self_write);

  -- Member gets basic permissions
  INSERT INTO role_permissions (role_id, permission_id) VALUES
    (v_member_role_id, p_order_read),
    (v_member_role_id, p_order_write),
    (v_member_role_id, p_wo_read),
    (v_member_role_id, p_outcome_read),
    (v_member_role_id, p_interaction_write),
    (v_member_role_id, p_auth_self_write);

  -- Technician gets work order permissions
  INSERT INTO role_permissions (role_id, permission_id) VALUES
    (v_tech_role_id, p_wo_read),
    (v_tech_role_id, p_wo_write),
    (v_tech_role_id, p_order_read),
    (v_tech_role_id, p_auth_self_write);

  -- --------------------------------------------------------
  -- Admin user
  -- --------------------------------------------------------
  v_admin_user_id := gen_random_uuid();
  INSERT INTO users (id, username, password_hash, status) VALUES
    (v_admin_user_id, 'admin', '$2b$10$Qr6Msk/fvrZYkWHlpIozBO8P98Q6m2YqCpmMvvoUy/RzEdihBZMu6', 'ACTIVE');

  INSERT INTO user_roles (user_id, role_id) VALUES
    (v_admin_user_id, v_admin_role_id);

  -- Also create member record for admin
  INSERT INTO members (id, user_id, status, tier, points_balance) VALUES
    (gen_random_uuid(), v_admin_user_id, 'ACTIVE', 'GOLD', 9999);

END $$;

-- --------------------------------------------------------
-- Sample inventory items
-- --------------------------------------------------------
INSERT INTO inventory (sku, on_hand, reserved) VALUES
  ('SKU-001', 100, 0),
  ('SKU-002', 50,  0),
  ('SKU-003', 200, 0),
  ('SKU-004', 0,   0),
  ('SKU-005', 75,  0);

-- --------------------------------------------------------
-- Sample coupons
-- --------------------------------------------------------
INSERT INTO coupons (id, code, type, threshold_amount, discount_amount, discount_percent, max_discount_amount, priority, active_from, active_to, is_active) VALUES
  (gen_random_uuid(), 'WELCOME10',  'PERCENTAGE_DISCOUNT',  NULL,   NULL, 10.00, 25.00, 10,
   now(), now() + INTERVAL '1 year', true),
  (gen_random_uuid(), 'SAVE50',     'THRESHOLD_DISCOUNT',   500.00, 50.00, NULL, NULL,   20,
   now(), now() + INTERVAL '1 year', true),
  (gen_random_uuid(), 'FREESHIP',   'SHIPPING_WAIVER',      200.00, NULL,  NULL, NULL,   5,
   now(), now() + INTERVAL '6 months', true);

-- --------------------------------------------------------
-- Sample campaigns
-- --------------------------------------------------------
INSERT INTO campaigns (id, name, type, params, priority, active_from, active_to, is_active) VALUES
  (gen_random_uuid(), 'Second Item 50% Off',
   'SECOND_ITEM_DISCOUNT',
   '{"discountPercent": 50}',
   10, now(), now() + INTERVAL '1 year', true),
  (gen_random_uuid(), 'Spend 1000 Get 200 Off',
   'SPEND_AND_GET',
   '{"spendThreshold": 1000, "rewardAmount": 200}',
   20, now(), now() + INTERVAL '1 year', true);

-- --------------------------------------------------------
-- Operational parameters
-- --------------------------------------------------------
INSERT INTO operational_params (key, value_json, updated_at) VALUES
  ('sla_first_response_hours', '4',                                    now()),
  ('sla_resolution_days',      '3',                                    now()),
  ('business_hours_start',     '"09:00"',                              now()),
  ('business_hours_end',       '"17:00"',                              now()),
  ('business_days',            '[1,2,3,4,5]',                         now()),
  ('sensitive_words',          '["spam","abuse","scam","offensive"]',  now()),
  ('points_per_dollar',        '1',                                    now()),
  ('tier_silver_threshold',    '1000',                                 now()),
  ('tier_gold_threshold',      '5000',                                 now()),
  ('order_timeout_minutes',    '30',                                   now());
