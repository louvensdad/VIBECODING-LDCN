-- Security Guardian findings and append-only audit trail.
--
-- Findings are evidence-backed records of security issues discovered in the project.
-- Audit events provide a tamper-evident, append-only chronological log of all security-sensitive actions.

CREATE TABLE security_findings (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  source_type VARCHAR(40) NOT NULL,
  source_id VARCHAR(120) NOT NULL,
  category VARCHAR(60) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  status VARCHAR(30) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  evidence TEXT NOT NULL,
  location VARCHAR(300) NOT NULL,
  recommendation VARCHAR(1000) NOT NULL,
  rule_id VARCHAR(50) NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  occurrence_count INT NOT NULL DEFAULT 1,
  resolution_reason VARCHAR(500),
  first_detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_security_findings_fingerprint ON security_findings(project_id, fingerprint);
CREATE INDEX idx_security_findings_project ON security_findings(project_id, status, severity);

CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  -- SET NULL, not CASCADE: an audit trail that disappears with the thing it audits is not a
  -- record of anything. The event outlives the project and the account it refers to.
  project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
  actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  event_type VARCHAR(60) NOT NULL,
  target_type VARCHAR(60) NOT NULL,
  target_id VARCHAR(120) NOT NULL,
  result VARCHAR(40) NOT NULL,
  metadata TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_events_project_created ON audit_events(project_id, created_at DESC);
CREATE INDEX idx_audit_events_actor ON audit_events(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_events_type ON audit_events(event_type, created_at DESC);
