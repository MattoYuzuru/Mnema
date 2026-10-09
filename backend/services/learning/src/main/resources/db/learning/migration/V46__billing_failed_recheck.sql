-- Billing review fixes (#389): the reconciler also re-checks FAILED orders that have a bank payment for 72 hours after they were created (a late or second
-- attempt on the bank's form may still pay them; PaymentReconciliation.FAILED_WINDOW). This index keeps that part of its claim query off the full table, next
-- to billing_order_open for the unfinished orders.
--
-- No status is added: REVIEW now also holds an order the bank kept in progress 72 hours past its link (failure_reason STALE) and one whose payment was partly
-- refunded without ever being granted (PARTIAL_REFUND); FAILED also holds an order whose Init the bank refused (INIT_REFUSED). failure_reason is free text.
CREATE INDEX billing_order_failed_recheck ON app_learning.billing_order(created_at) WHERE status = 'FAILED' AND payment_id IS NOT NULL;
