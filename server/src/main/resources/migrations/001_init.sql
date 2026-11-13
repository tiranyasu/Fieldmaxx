CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL,
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE login_failures (
  username TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE TABLE jobs (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  field_notes_url TEXT,
  status TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  finished_at INTEGER,
  deleted_at INTEGER,
  deleted_by TEXT,
  delivery_problem TEXT
);
CREATE TABLE job_files (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES jobs(id),
  kind TEXT NOT NULL,
  original_name TEXT NOT NULL,
  stored_path TEXT NOT NULL,
  layer TEXT,
  uploaded_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE imports (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  file_name TEXT NOT NULL,
  kind TEXT NOT NULL,
  stored_path TEXT NOT NULL,
  result_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE ref_images (
  id TEXT PRIMARY KEY,
  job_id TEXT,
  import_id TEXT,
  kind TEXT NOT NULL,
  page INTEGER,
  stored_path TEXT NOT NULL,
  mime TEXT NOT NULL
);
CREATE TABLE points (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES jobs(id),
  layer TEXT NOT NULL,
  type TEXT NOT NULL,
  num TEXT NOT NULL,
  label TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  orig_lat REAL,
  orig_lon REAL,
  description TEXT,
  status TEXT NOT NULL,
  cant_reason TEXT,
  cant_text TEXT,
  pole_json TEXT,
  comment TEXT,
  folder_path TEXT,
  folder_candidates_json TEXT,
  ref_image_ids_json TEXT,
  flags_json TEXT,
  source_file_id TEXT,
  source_page INTEGER,
  added_by TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT,
  deleted_at INTEGER,
  deleted_by TEXT
);
CREATE TABLE photos (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES jobs(id),
  point_id TEXT NOT NULL REFERENCES points(id),
  user_id TEXT NOT NULL,
  file_name TEXT NOT NULL,
  size INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT,
  taken_at INTEGER NOT NULL,
  lat REAL,
  lon REAL,
  accuracy_m REAL,
  heading_deg REAL,
  status TEXT NOT NULL,
  dest_path TEXT,
  last_error TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER,
  backup_path TEXT,
  backup_offset INTEGER NOT NULL DEFAULT 0,
  backup_received_at INTEGER,
  backup_purged_at INTEGER,
  thumb_path TEXT,
  confirmed_at INTEGER,
  upload_ms INTEGER,
  reused_from TEXT,
  source_kind TEXT,
  removed_at INTEGER,
  removed_by TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE counters (
  name TEXT PRIMARY KEY,
  value INTEGER NOT NULL
);
CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE ops_done (
  op_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  at INTEGER NOT NULL,
  result_json TEXT
);
CREATE TABLE changes (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id TEXT,
  entity TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  data_json TEXT,
  at INTEGER NOT NULL
);
CREATE TABLE positions (
  user_id TEXT PRIMARY KEY,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  accuracy_m REAL,
  altitude_m REAL,
  at INTEGER NOT NULL,
  paused INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id TEXT,
  user_id TEXT,
  kind TEXT NOT NULL,
  data_json TEXT,
  at INTEGER NOT NULL
);
CREATE TABLE app_releases (
  version_code INTEGER PRIMARY KEY,
  version_name TEXT NOT NULL,
  file_path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_points_job ON points(job_id);
CREATE INDEX idx_photos_point ON photos(point_id);
CREATE INDEX idx_photos_status_next ON photos(status, next_attempt_at);
CREATE INDEX idx_photos_latlon ON photos(lat, lon);
CREATE INDEX idx_changes_job ON changes(job_id);
