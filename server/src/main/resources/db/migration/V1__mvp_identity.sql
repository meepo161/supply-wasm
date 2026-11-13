CREATE TABLE departments
(
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    parent_id  BIGINT REFERENCES departments (id),
    head_id    BIGINT
);

CREATE INDEX idx_departments_code ON departments (code);
CREATE INDEX idx_departments_parent ON departments (parent_id);

CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    login         VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(255),
    department_id BIGINT REFERENCES departments (id),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    BIGINT REFERENCES users (id),
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_users_login ON users (login);
CREATE INDEX idx_users_department ON users (department_id);

ALTER TABLE departments
    ADD CONSTRAINT fk_departments_head FOREIGN KEY (head_id) REFERENCES users (id);

CREATE TABLE roles
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT
);

CREATE TABLE user_roles
(
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE permissions
(
    id        BIGSERIAL PRIMARY KEY,
    role_id   BIGINT      NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    resource  VARCHAR(64) NOT NULL,
    action    VARCHAR(64) NOT NULL,
    scope     VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (role_id, resource, action, scope)
);

CREATE INDEX idx_permissions_role ON permissions (role_id);

CREATE TABLE sessions
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    data       JSONB       NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_sessions_user ON sessions (user_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);

CREATE TABLE audit_log
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users (id),
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id   BIGINT      NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, created_at);
CREATE INDEX idx_audit_created ON audit_log (created_at);
