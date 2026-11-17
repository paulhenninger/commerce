CREATE TABLE payments (
  id              CHAR(26)      NOT NULL PRIMARY KEY,
  -- One payment per order: the unique key is what makes a repeated charge
  -- request, or a charge after a cancellation, impossible to record twice.
  order_id        CHAR(26)      NOT NULL,
  user_id         BIGINT        NOT NULL,
  amount_cents    BIGINT        NOT NULL,
  currency        CHAR(3)       NOT NULL,
  -- CANCELLED: the order was cancelled before any charge; a later charge
  -- request for it is ignored.
  status          ENUM('AUTHORIZED', 'FAILED', 'REFUNDED', 'CANCELLED') NOT NULL,
  provider_ref    VARCHAR(100)  NULL,
  refund_ref      VARCHAR(100)  NULL,
  failure_reason  VARCHAR(40)   NULL,
  created_at      DATETIME(6)   NOT NULL,
  updated_at      DATETIME(6)   NOT NULL,
  UNIQUE KEY uq_payments_order (order_id),
  KEY idx_payments_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
