CREATE TABLE projects
(
    id            BIGSERIAL PRIMARY KEY,
    number        VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    owner_id      BIGINT       NOT NULL REFERENCES users (id),
    department_id BIGINT REFERENCES departments (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    BIGINT       NOT NULL REFERENCES users (id),
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_projects_number ON projects (number);
CREATE INDEX idx_projects_owner ON projects (owner_id);
CREATE INDEX idx_projects_department ON projects (department_id);

CREATE TABLE project_members
(
    project_id BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    added_by   BIGINT      NOT NULL REFERENCES users (id),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX idx_project_members_user ON project_members (user_id);
