CREATE TABLE stock (
  sku         VARCHAR(40)  NOT NULL PRIMARY KEY,
  on_hand     INT          NOT NULL,
  -- Held for orders that are not yet confirmed or cancelled.
  reserved    INT          NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  CONSTRAINT chk_stock_counts CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservations (
  order_id    CHAR(26)     NOT NULL,
  sku         VARCHAR(40)  NOT NULL,
  quantity    INT          NOT NULL,
  status      ENUM('RESERVED', 'COMMITTED', 'RELEASED') NOT NULL,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (order_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
