-- Identity. One row per person who can sign in.
--
-- The password column stores a PasswordEncoder hash and nothing else: no plaintext, no
-- reversible encoding. The column is named password_hash so that a query written by hand
-- cannot mistake it for a password.
CREATE TABLE users (
  id UUID PRIMARY KEY,
  -- Stored already normalized (trimmed, lowercased). The unique index is on the stored value,
  -- so the database — not only Java — rejects a second account for the same address.
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(200) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  role VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_login_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_users_email ON users(email);
