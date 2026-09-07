-- Every project gets an owner.
--
-- Projects created before identity existed have no owner and cannot get a truthful one:
-- inventing an owner would hand someone else's data to whoever the migration picked. So the
-- column is nullable ONLY to preserve those pre-existing rows, and nothing is deleted.
--
-- A project with owner_user_id IS NULL is unreachable: the access policy denies by default, so
-- no user can read or write it. It is retained, not hidden, and can be claimed deliberately:
--
--   UPDATE projects SET owner_user_id = '<user-uuid>' WHERE owner_user_id IS NULL;
--
-- Every project created from now on has an owner — the application refuses to build one without.
-- The foreign key guarantees that an owner, when present, is a real user.
ALTER TABLE projects ADD COLUMN owner_user_id UUID REFERENCES users(id) ON DELETE RESTRICT;

CREATE INDEX idx_projects_owner ON projects(owner_user_id, created_at DESC);
