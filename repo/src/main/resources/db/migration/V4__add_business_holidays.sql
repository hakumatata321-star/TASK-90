-- ============================================================
-- V4 – Add business_holidays operational param (Q10)
--
-- Stores an array of ISO-8601 date strings to exclude from SLA
-- calculations.  Starts empty; admins populate it via the
-- operational-params API as needed.
-- ============================================================

INSERT INTO operational_params (key, value_json, updated_at)
VALUES ('business_holidays', '[]', now())
ON CONFLICT (key) DO NOTHING;
