-- V7 – Additional demo users for MEMBER and TECHNICIAN roles
DO $$
DECLARE
  v_member_role_id UUID;
  v_tech_role_id   UUID;
  v_member_user_id UUID;
  v_tech_user_id   UUID;
BEGIN
  SELECT id INTO v_member_role_id FROM roles WHERE name = 'MEMBER'     LIMIT 1;
  SELECT id INTO v_tech_role_id   FROM roles WHERE name = 'TECHNICIAN' LIMIT 1;

  -- member user  (password: Member@1234!)
  v_member_user_id := gen_random_uuid();
  INSERT INTO users (id, username, password_hash, status) VALUES
    (v_member_user_id, 'member', '$2b$10$Y5LE4cnp/Ms.zHeWmSy/ruQImtKLBrZ8WTj.DoTB7dVQsjcwTqoMO', 'ACTIVE');
  INSERT INTO user_roles (user_id, role_id) VALUES (v_member_user_id, v_member_role_id);
  INSERT INTO members (id, user_id, status, tier, points_balance) VALUES
    (gen_random_uuid(), v_member_user_id, 'ACTIVE', 'BRONZE', 0);

  -- technician user  (password: Tech@1234!)
  v_tech_user_id := gen_random_uuid();
  INSERT INTO users (id, username, password_hash, status) VALUES
    (v_tech_user_id, 'technician', '$2b$10$ZMJIBHDDfSuzWXc551T85ee4QFh7Rmf870n1wfREpvwFLKk3xHyVi', 'ACTIVE');
  INSERT INTO user_roles (user_id, role_id) VALUES (v_tech_user_id, v_tech_role_id);
END $$;
