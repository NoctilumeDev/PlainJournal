UPDATE user_benefit
SET locked_order_no = NULL, locked_at = NULL
WHERE status IN ('AVAILABLE', 'REDEEMED')
  AND (locked_order_no IS NOT NULL OR locked_at IS NOT NULL);
