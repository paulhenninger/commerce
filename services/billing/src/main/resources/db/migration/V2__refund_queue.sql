-- Refunds are queued, then made by RefundWorker outside any transaction and
-- retried until the provider takes them (ADR-031). Before this, a refund the
-- provider could not take was dead-lettered and waited for an operator.
ALTER TABLE payments
  MODIFY status ENUM('AUTHORIZED', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'CANCELLED') NOT NULL,
  ADD COLUMN refund_attempts        INT           NOT NULL DEFAULT 0,
  ADD COLUMN refund_next_attempt_at DATETIME(6)   NULL,
  ADD COLUMN refund_last_error      VARCHAR(200)  NULL,
  ADD KEY idx_payments_refund_due (status, refund_next_attempt_at);
