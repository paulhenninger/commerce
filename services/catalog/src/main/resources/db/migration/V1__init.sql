CREATE TABLE products (
  sku          VARCHAR(40)   NOT NULL PRIMARY KEY,
  name         VARCHAR(200)  NOT NULL,
  description  TEXT          NOT NULL,
  category     VARCHAR(64)   NOT NULL,
  price_cents  BIGINT        NOT NULL,
  currency     CHAR(3)       NOT NULL,
  image_key    VARCHAR(200)  NULL,
  active       BOOLEAN       NOT NULL,
  -- How many can be ordered right now: a copy of what the inventory service
  -- announces, so browsing never has to ask it.
  available    INT           NOT NULL DEFAULT 0,
  created_at   DATETIME(6)   NOT NULL,
  updated_at   DATETIME(6)   NOT NULL,
  KEY idx_products_active_category (active, category, name),
  KEY idx_products_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
