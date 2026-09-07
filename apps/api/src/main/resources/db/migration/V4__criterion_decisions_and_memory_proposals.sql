-- A satisfied acceptance criterion must name who vouched for it. Criteria are decided
-- explicitly and are never inferred from a passing build.
ALTER TABLE task_acceptance_criteria ADD COLUMN decided_by VARCHAR(80);
ALTER TABLE task_acceptance_criteria ADD COLUMN decided_at TIMESTAMP WITH TIME ZONE;

-- Proposed memory. Nothing here is official project memory: a proposal becomes a brain_entry
-- only when a person accepts it, and the resulting entry is linked back for traceability.
CREATE TABLE memory_update_proposals (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  trigger_event VARCHAR(40) NOT NULL,
  proposed_type VARCHAR(40) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  source VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  resulting_entry_id UUID REFERENCES brain_entries(id) ON DELETE SET NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  reviewed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_memory_proposals_project_status
  ON memory_update_proposals(project_id, status, created_at DESC);
