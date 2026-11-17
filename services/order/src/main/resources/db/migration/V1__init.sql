CREATE TABLE orders (
  id                      CHAR(26)      NOT NULL PRIMARY KEY,
  user_id                 BIGINT        NOT NULL,
  email                   VARCHAR(254)  NOT NULL,
  status                  VARCHAR(20)   NOT NULL,
  total_cents             BIGINT        NOT NULL,
  currency                CHAR(3)       NOT NULL,
  -- An opaque token from the payment provider's checkout widget; never card data.
  payment_method          VARCHAR(64)   NOT NULL,
  shipping_address        JSON          NOT NULL,
  cancel_reason           VARCHAR(40)   NULL,
  tracking_number         VARCHAR(64)   NULL,
  return_tracking_number  VARCHAR(64)   NULL,
  return_reason           VARCHAR(500)  NULL,
  idempotency_key         VARCHAR(64)   NOT NULL,
  created_at              DATETIME(6)   NOT NULL,
  updated_at              DATETIME(6)   NOT NULL,
  delivered_at            DATETIME(6)   NULL,
  -- A retried checkout (same key) finds the first order instead of making a second.
  UNIQUE KEY uq_orders_idempotency (user_id, idempotency_key),
  KEY idx_orders_user (user_id, created_at),
  -- The saga timeout and the in-flight count scan by status and age.
  KEY idx_orders_status (status, created_at),
  KEY idx_orders_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_lines (
  order_id          CHAR(26)      NOT NULL,
  line_no           INT           NOT NULL,
  sku               VARCHAR(40)   NOT NULL,
  name              VARCHAR(200)  NOT NULL,
  quantity          INT           NOT NULL,
  unit_price_cents  BIGINT        NOT NULL,
  PRIMARY KEY (order_id, line_no),
  CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_history (
  id        BIGINT        AUTO_INCREMENT PRIMARY KEY,
  order_id  CHAR(26)      NOT NULL,
  status    VARCHAR(20)   NOT NULL,
  detail    VARCHAR(500)  NOT NULL,
  at        DATETIME(6)   NOT NULL,
  KEY idx_order_history_order (order_id, id),
  CONSTRAINT fk_order_history_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The order service's own copy of the catalog's prices, kept from product events,
-- so placing an order never asks the catalog service anything.
CREATE TABLE products (
  sku          VARCHAR(40)   NOT NULL PRIMARY KEY,
  name         VARCHAR(200)  NOT NULL,
  price_cents  BIGINT        NOT NULL,
  currency     CHAR(3)       NOT NULL,
  active       BOOLEAN       NOT NULL,
  updated_at   DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
