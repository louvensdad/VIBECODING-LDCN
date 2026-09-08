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

  -- The compiler's digest over the pack's canonical payload: policy version, budget, item order,
  -- and per item its kind, provenance, redacted content, admitting rule id and that rule's
  -- explanation. ALSO DESCRIPTIVE ONLY, and under exactly the same prohibitions as
  -- content_fingerprint above: no uniqueness, no index, no foreign key, no row ever found by it.
  --
  -- The two are not interchangeable and neither replaces the other. content_fingerprint covers
  -- what one pack's items say; this covers the whole compilation, so a policy change that admits
  -- the same text under a different rule moves this one and leaves that one alone. That difference
  -- is the reason both are here.
  --
  -- NOT NULL because every pack is produced by the compiler and the compiler always computes one.
  -- A nullable column could therefore only ever record that a write path lost it, and it would
  -- read as "this pack has no digest" -- which is a claim about the pack rather than about the
  -- bug, and the wrong one.
  --
  -- VARCHAR(64) because SHA-256 in lowercase hex is exactly 64 characters, always.
  pack_digest VARCHAR(64) NOT NULL,

  -- The policy version in force when this pack's items were admitted. Stored so that a reader
  -- comparing an old pack with a new one can see whether the rules moved, instead of assuming the
  -- project's state did. Without it the only available explanation for a difference is "the
  -- records changed", which is usually wrong and always unprovable.
  --
  -- Deliberately NOT the application version. Tying it to the build would restamp every pack on
  -- every release whether or not a single rule moved, and would leave two packs compiled by
  -- identical rules claiming to have been compiled under different policies.
  --
  -- NOT NULL because there is no path that compiles a pack without a policy: the compiler takes
  -- one, and it stamps what it used. A null here would describe an impossible pack.
  --
  -- VARCHAR(20) matches ContextPolicyVersion, which caps the label at 20 for the same reason: it
  -- is an opaque handle compared for equality, not a description and not something to sort by.
  policy_version VARCHAR(20) NOT NULL,

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

  -- The admission that let this item in: which rule decided, and what that rule said. Kept as a
  -- historical fact rather than re-derived on read. A pack is a snapshot, and asking today's
  -- policy why an item was admitted two versions ago yields an answer that sounds authoritative
  -- and is about a different set of rules.
  --
  -- There is deliberately NO decision column. A denied item is never written -- its content does
  -- not reach this table at all, which is the entire point of denying it -- so every row here is
  -- an allow by construction. A column repeating that on every row would carry no information,
  -- and would invite someone to start storing denials "just for the record", which would put the
  -- text policy refused into the database in the clear.
  --
  -- Widths follow the convention set above: policy_rule_id is an identifier a person types, so
  -- 200; explanation is human text that is displayed, so 500, like label and task_reference.
  --
  -- Both NOT NULL, and that pairing is the point rather than an accident. The rule id is what
  -- makes a reason traceable to something a reader can look up and disagree with; the explanation
  -- is what makes it readable at all. An item stored with either one missing is an item nobody can
  -- account for, which is precisely the state this engine exists to prevent -- so the database
  -- refuses it here, rather than trusting every future write path to remember.
  policy_rule_id VARCHAR(200) NOT NULL,
  explanation VARCHAR(500) NOT NULL,

  -- Uniqueness is not validity: without this check a position of -1 is as acceptable to the
  -- database as 0, and a pack whose first item claims a place before the beginning would load
  -- looking ordered. Positions count from zero.
  CONSTRAINT ck_context_pack_items_position CHECK (item_position >= 0),

  -- The order is a property of the pack, so the database enforces that no two items claim the
  -- same place and that a pack never holds the same item id twice.
  CONSTRAINT uq_context_pack_items_position UNIQUE (pack_id, item_position),
  CONSTRAINT uq_context_pack_items_item_id UNIQUE (pack_id, item_id)
);
