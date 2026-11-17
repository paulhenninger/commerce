CREATE TABLE shipments (
  id               CHAR(26)      NOT NULL PRIMARY KEY,
  order_id         CHAR(26)      NOT NULL,
  direction        ENUM('OUTBOUND', 'RETURN') NOT NULL,
  -- outbound: PENDING → CREATED → DISPATCHED → DELIVERED (or CANCELLED)
  -- return:   PENDING → CREATED → IN_TRANSIT → RECEIVED
  status           VARCHAR(20)   NOT NULL,
  user_id          BIGINT        NOT NULL,
  email            VARCHAR(254)  NOT NULL,
  address          JSON          NOT NULL,
  carrier          VARCHAR(40)   NULL,
  tracking_number  VARCHAR(64)   NULL,
  attempts         INT           NOT NULL DEFAULT 0,
  last_error       VARCHAR(200)  NULL,
  -- When the worker next looks at it: due work, a retry after a failure, or the
  -- end of another worker's lease.
  next_attempt_at  DATETIME(6)   NOT NULL,
  created_at       DATETIME(6)   NOT NULL,
  updated_at       DATETIME(6)   NOT NULL,
  UNIQUE KEY uq_shipments_order_direction (order_id, direction),
  KEY idx_shipments_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
