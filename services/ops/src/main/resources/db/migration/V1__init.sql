-- Every alert Alertmanager reports becomes an incident: who is on it, what they
-- did, and when it ended.
CREATE TABLE incidents (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  fingerprint      VARCHAR(64)   NOT NULL,
  alertname        VARCHAR(100)  NOT NULL,
  severity         VARCHAR(20)   NOT NULL,
  service          VARCHAR(64)   NULL,
  summary          VARCHAR(500)  NOT NULL,
  description      TEXT          NULL,
  runbook_url      VARCHAR(500)  NULL,
  labels           JSON          NOT NULL,
  status           ENUM('FIRING', 'RESOLVED') NOT NULL,
  starts_at        DATETIME(6)   NOT NULL,
  ends_at          DATETIME(6)   NULL,
  acknowledged_by  VARCHAR(254)  NULL,
  acknowledged_at  DATETIME(6)   NULL,
  updated_at       DATETIME(6)   NOT NULL,
  -- One incident per firing of an alert: the same alert firing again later is a new incident.
  UNIQUE KEY uq_incidents_firing (fingerprint, starts_at),
  KEY idx_incidents_status (status, starts_at),
  KEY idx_incidents_service (service, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE incident_notes (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
  incident_id  BIGINT        NOT NULL,
  author       VARCHAR(254)  NOT NULL,
  note         TEXT          NOT NULL,
  at           DATETIME(6)   NOT NULL,
  KEY idx_incident_notes_incident (incident_id, id),
  CONSTRAINT fk_incident_notes_incident FOREIGN KEY (incident_id) REFERENCES incidents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
