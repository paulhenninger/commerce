CREATE TABLE notifications (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  -- One email per event: a redelivered event finds its row and adds nothing.
  event_id         VARCHAR(64)   NOT NULL,
  order_id         CHAR(26)      NULL,
  recipient        VARCHAR(254)  NOT NULL,
  template         VARCHAR(40)   NOT NULL,
  subject          VARCHAR(200)  NOT NULL,
  body             TEXT          NOT NULL,
  status           ENUM('PENDING', 'SENT', 'FAILED') NOT NULL,
  attempts         INT           NOT NULL DEFAULT 0,
  last_error       VARCHAR(200)  NULL,
  next_attempt_at  DATETIME(6)   NOT NULL,
  created_at       DATETIME(6)   NOT NULL,
  sent_at          DATETIME(6)   NULL,
  UNIQUE KEY uq_notifications_event (event_id),
  KEY idx_notifications_due (status, next_attempt_at),
  KEY idx_notifications_recipient (recipient, created_at),
  KEY idx_notifications_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
