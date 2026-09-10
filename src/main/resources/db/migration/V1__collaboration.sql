CREATE TABLE app_users (
  id uuid PRIMARY KEY,
  email varchar(254) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE documents (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES app_users(id),
  title varchar(160) NOT NULL,
  last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence >= 0),
  max_lamport bigint NOT NULL DEFAULT 0,
  pending_count integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX documents_owner ON documents(owner_id, created_at DESC);
CREATE TABLE document_members (
  document_id uuid NOT NULL REFERENCES documents(id),
  user_id uuid NOT NULL REFERENCES app_users(id),
  role varchar(10) NOT NULL CHECK(role IN ('EDITOR','VIEWER')),
  PRIMARY KEY(document_id,user_id)
);
CREATE INDEX members_user ON document_members(user_id, document_id);
CREATE TABLE editing_actors (
  id uuid PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES documents(id),
  user_id uuid NOT NULL REFERENCES app_users(id),
  last_sequence bigint NOT NULL DEFAULT 0,
  last_lamport bigint NOT NULL DEFAULT 0,
  generation bigint NOT NULL DEFAULT 1
);
CREATE INDEX actors_document ON editing_actors(document_id);
CREATE TABLE websocket_tickets (
  hash char(64) PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES documents(id),
  user_id uuid NOT NULL REFERENCES app_users(id),
  actor_id uuid NOT NULL REFERENCES editing_actors(id),
  generation bigint NOT NULL,
  expires_at timestamptz NOT NULL,
  session_expires_at timestamptz NOT NULL
);
CREATE INDEX tickets_expiry ON websocket_tickets(expires_at);
CREATE TABLE operations (
  document_id uuid NOT NULL REFERENCES documents(id),
  actor_id uuid NOT NULL REFERENCES editing_actors(id),
  operation_sequence bigint NOT NULL CHECK(operation_sequence > 0),
  server_sequence bigint NOT NULL CHECK(server_sequence > 0),
  payload text NOT NULL CHECK(jsonb_typeof(payload::jsonb) = 'object'),
  checksum char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(document_id,actor_id,operation_sequence),
  UNIQUE(document_id,server_sequence)
);
CREATE TABLE snapshots (
  document_id uuid NOT NULL REFERENCES documents(id),
  watermark bigint NOT NULL,
  state text NOT NULL CHECK(jsonb_typeof(state::jsonb) = 'object'),
  checksum char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(document_id,watermark)
);
