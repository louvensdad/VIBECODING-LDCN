-- Encrypted credential storage.
--
-- No column here holds a secret in the clear. The ciphertext, the nonce and the wrapped data key
-- are binary and meaningless without the master key, which lives outside the database entirely.

CREATE TABLE vault_secrets (
  id UUID PRIMARY KEY,
  -- RESTRICT, not CASCADE: deleting a user must not silently destroy credential material and the
  -- record that it existed. Removal is an explicit operation with its own audit event.
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  purpose VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,

  -- Which version is currently in force. One nullable column, so "at most one active version"
  -- is a structural fact rather than a rule something has to keep. The foreign key is added
  -- after vault_secret_versions exists, because the two tables point at each other.
  active_version_id UUID,

  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_vault_secrets_owner ON vault_secrets(owner_user_id, purpose);

CREATE TABLE vault_secret_versions (
  id UUID PRIMARY KEY,
  secret_id UUID NOT NULL REFERENCES vault_secrets(id) ON DELETE RESTRICT,
  version_number INTEGER NOT NULL,

  -- Binary, not base64 text: encoding ciphertext as a string only makes it larger and easier to
  -- copy somewhere it does not belong.
  ciphertext BYTEA NOT NULL,
  nonce BYTEA NOT NULL,
  wrapped_data_key BYTEA NOT NULL,

  -- Recorded per row so a future format or key-provider change can find what needs migrating.
  -- "We know we used AES" is not a recovery plan.
  algorithm VARCHAR(40) NOT NULL,
  format_version SMALLINT NOT NULL,
  key_provider VARCHAR(40) NOT NULL,
  key_version VARCHAR(60) NOT NULL,

  -- Descriptive: ACTIVE, RETIRED or DESTROYED. Which version is *in force* is decided by
  -- vault_secrets.active_version_id, not by this column, so a rotation can write and durably
  -- flush its replacement before anything about the old version changes.
  status VARCHAR(20) NOT NULL,

  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  retired_at TIMESTAMP WITH TIME ZONE,

  UNIQUE (secret_id, version_number)
);

-- Closes the cycle: a secret may only point at a version that exists. RESTRICT, because a
-- version that some secret still names must not disappear underneath it.
ALTER TABLE vault_secrets
  ADD CONSTRAINT fk_vault_secrets_active_version
  FOREIGN KEY (active_version_id) REFERENCES vault_secret_versions(id) ON DELETE RESTRICT;

CREATE INDEX idx_vault_versions_secret ON vault_secret_versions(secret_id, version_number DESC);

-- A connection to a model provider. Holds a reference to credential material, never the material.
CREATE TABLE provider_accounts (
  id UUID PRIMARY KEY,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  provider VARCHAR(40) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  authentication_type VARCHAR(40) NOT NULL,
  status VARCHAR(40) NOT NULL,

  -- A pointer into the vault. SET NULL so removing a credential cannot orphan the account row.
  credential_secret_id UUID REFERENCES vault_secrets(id) ON DELETE SET NULL,
  credential_updated_at TIMESTAMP WITH TIME ZONE,

  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_provider_accounts_owner ON provider_accounts(owner_user_id, provider);
