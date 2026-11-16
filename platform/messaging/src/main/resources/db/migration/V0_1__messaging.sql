-- Shipped by platform:messaging and applied before each service's own V1.

-- Events waiting to be published. A row is inserted in the same transaction as
-- the state change it announces and deleted once the broker has acknowledged it,
-- so the table is normally empty; its size and oldest row are alerted on.
CREATE TABLE outbox_events (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  topic        VARCHAR(128) NOT NULL,
  record_key   VARCHAR(64)  NOT NULL,
  event_type   VARCHAR(64)  NOT NULL,
  payload      MEDIUMBLOB   NOT NULL,
  traceparent  VARCHAR(64)  NULL,
  created_at   DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Every event this service has applied. The primary key is what makes a
-- redelivered or replayed event a no-op; rows older than the topics' retention
-- are purged nightly.
CREATE TABLE processed_events (
  consumer     VARCHAR(64) NOT NULL,
  event_id     VARCHAR(64) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (consumer, event_id),
  KEY idx_processed_at (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
