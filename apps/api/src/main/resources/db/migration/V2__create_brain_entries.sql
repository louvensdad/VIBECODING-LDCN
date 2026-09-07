CREATE TABLE brain_entries (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  source VARCHAR(80) NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_brain_entries_project_created ON brain_entries(project_id, created_at);
