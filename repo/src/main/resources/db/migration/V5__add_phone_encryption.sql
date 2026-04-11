-- V5: Add phone_masked and phone_encrypted columns to users table
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone_masked  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS phone_encrypted TEXT;

-- Functional index on the masked phone for fast lookups (e.g. "****1234")
CREATE INDEX IF NOT EXISTS idx_users_phone_masked ON users (phone_masked)
    WHERE phone_masked IS NOT NULL;
