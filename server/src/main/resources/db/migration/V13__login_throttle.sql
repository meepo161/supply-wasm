CREATE TABLE login_attempts
(
    id               BIGSERIAL PRIMARY KEY,
    normalized_login VARCHAR(64) NOT NULL,
    ip_address       VARCHAR(64) NOT NULL,
    attempted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_login_attempts_lookup ON login_attempts (normalized_login, ip_address, attempted_at);
