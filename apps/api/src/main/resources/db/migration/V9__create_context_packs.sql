-- Deterministic Context Engine: the ContextPack snapshot and the items it selected.
--
-- A pack is a record of a decision already taken, not a working set. Nothing here is updated in
-- place: a pack is written whole, read whole, and superseded by a later pack rather than edited.
--
-- Only redacted content is stored. There is deliberately no raw_content column and no shadow
-- table holding the pre-redaction text: redaction happens before persistence, so a column that
-- could carry the unredacted version would move the security boundary from "the value never
-- reaches the database" to "something remembers to clean it up later".

CREATE TABLE context_packs (
  -- The pack's persistent identity, and the only one. See ContextPack.packId.
  id UUID PRIMARY KEY,

  -- CASCADE, which is neither of this codebase's two conventions. The reason is the payload, not
  -- the pack's status as a record: a pack IS a record of a decision — what was put in front of
  -- the work, and when — so "it is merely derived" would be the wrong argument, and by that test
  -- audit_events would be derived too.
  --
  -- What separates the three cases is what each row actually holds:
  --   * audit_events.project_id is SET NULL because what survives is metadata — who did what,
  --     when — which is safe to retain indefinitely and useless to retain partially.
  --   * vault_secrets.owner_user_id is RESTRICT because destroying credential material as a side
  --     effect of deleting a user is worse than refusing the delete.
  --   * a context pack holds a full copy of the project's own prose. Keeping that after "delete
  --     my project" is a retention liability nobody asked for, and CASCADE is the only one of the
  --     three that makes the delete mean what the person clicking it thinks it means.
  --
  -- SET NULL is not available anyway: ContextPack requires a project id and every item's
  -- provenance must name the same project, so a nulled row is one the domain cannot load.
  --
  -- The accepted cost, stated rather than discovered later: once a project is deleted, "what
  -- context produced this surviving audit event" can no longer be answered. If that becomes
  -- important, the answer is a metadata-only record in audit_events — pack id, item count,
  -- fingerprint, no text — not a weaker foreign key here.
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

  -- The task the pack was assembled for. Part of what "the same inputs" means.
  --
  -- 500 rather than 200: this is human text and it attracts decoration. A caller composing
  -- "TASK-42: " + a task title already capped at 200 overflows a 200-wide column on its first
  -- run, and would find out as a constraint violation at write time — after assembly, policy and
  -- selection have all done their work. Note that ddl-auto=validate does NOT check column width,
  -- so nothing downstream will catch this for us; the width has to be right here.
  task_reference VARCHAR(500) NOT NULL,

  -- When the snapshot was taken, which is a fact about the pack. Distinct from created_at, the
  -- moment this row was written; they differ whenever a pack is assembled and stored separately.
  assembled_at TIMESTAMP WITH TIME ZONE NOT NULL,

  -- The ceiling the pack was held to, stored flat because a budget is three counted numbers and
  -- has no identity of its own. Kept with the pack so a reader can see what it was measured
  -- against without guessing at today's configuration. There is no token column: the budget has
  -- no token dimension, and an estimate stored as a limit would read as authoritative.
  budget_max_items INTEGER NOT NULL,
  budget_max_characters BIGINT NOT NULL,
  budget_max_bytes BIGINT NOT NULL,

  -- DESCRIPTIVE ONLY. This is ContextPack.contentFingerprint as it was computed when the pack was
  -- written: a digest of what the pack says, not of which pack it is. It carries NO uniqueness
  -- constraint, NO index and NO foreign key, and no row may ever be looked up by it. Two packs
  -- assembled from unchanged state share it by design, and changing what the digest covers
  -- changes every value ever computed — either property alone would make it a broken key. It is
  -- stored so a reader can see the digest under the definition in force at write time, which
  -- recomputing today would not give them.
  content_fingerprint VARCHAR(64) NOT NULL,

  -- When the row was written. No updated_at: a snapshot that could be updated is not a snapshot.
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_context_packs_project ON context_packs(project_id, assembled_at DESC);

CREATE TABLE context_pack_items (
  -- Row identity. Deliberately not the item's own id: ContextItem.id is unique within its pack
  -- and nowhere else, so two packs legitimately hold items sharing an id.
  id UUID PRIMARY KEY,

  -- CASCADE: an item has no meaning apart from the pack that selected it. A pack is written and
  -- deleted as one thing, so an orphaned item row could only ever be debris.
  pack_id UUID NOT NULL REFERENCES context_packs(id) ON DELETE CASCADE,

  -- The item's place in the pack's canonical order, written explicitly. A list persisted without
  -- an order column comes back in whatever order the database felt like, and the determinism the
  -- whole engine rests on would fail silently rather than loudly.
  item_position INTEGER NOT NULL,

  -- 200 for the two identifier columns, and that is a deliberate line rather than a default.
  -- item_id is an identifier constructed by a collector and source_id is a record's own id,
  -- usually a UUID; a collector emitting more than 200 characters of identifier has a defect that
  -- a wider column would hide. Human text gets 500 (see label below and task_reference above),
  -- identifiers get 200.
  item_id VARCHAR(200) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  -- Human text, and displayed: the same reasoning as task_reference.
  label VARCHAR(500) NOT NULL,

  -- The redacted text, and the only copy of it. See the note at the top of this file.
  content TEXT NOT NULL,

  -- Provenance, stored in full so an item can still be traced after the fact. Flattened rather
  -- than given its own table: provenance is part of what an item is, has no life without one, and
  -- a join here would buy nothing.
  source_type VARCHAR(40) NOT NULL,
  source_id VARCHAR(200) NOT NULL,
  -- Null where the underlying record carries no revision at all — see ContextSource.version.
  source_version INTEGER,
  -- Carried explicitly, exactly as the domain does, so an item can never be read as belonging to
  -- a project it was not drawn from. Intentionally NOT a foreign key: this is a copy of an
  -- assertion provenance made at the time, not a live reference to a current row. A foreign key
  -- would invite the database to enforce agreement with a row that may since have been
  -- legitimately superseded, which is not a property a historical record should have.
  -- context_packs.project_id is the one that must stay referentially true.
  provenance_project_id UUID NOT NULL,
  -- When the source state was observed, not when the pack was assembled.
  recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,

  -- Uniqueness is not validity: without this check a position of -1 is as acceptable to the
  -- database as 0, and a pack whose first item claims a place before the beginning would load
  -- looking ordered. Positions count from zero.
  CONSTRAINT ck_context_pack_items_position CHECK (item_position >= 0),

  -- The order is a property of the pack, so the database enforces that no two items claim the
  -- same place and that a pack never holds the same item id twice.
  CONSTRAINT uq_context_pack_items_position UNIQUE (pack_id, item_position),
  CONSTRAINT uq_context_pack_items_item_id UNIQUE (pack_id, item_id)
);
