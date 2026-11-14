-- V14: DMS core — persistent document cards, independent immutable versions,
-- separate immutable file storage, links to existing PROJECT/TASK/PRODUCT_MODEL/
-- BOM_REVISION/DEMAND, append-only version history and download audit.
--
-- Design choices aligned with the task:
-- * Document files are stored in a dedicated `document_files` table/storage key
--   and NOT mixed with TASK/PROJECT `attachments` (V4/V6). This avoids lifecycle
--   and permission coupling with chat/task attachments.
-- * `attachments.entity_type` in V4 has no CHECK constraint, so using it with
--   entity_type='DOCUMENT_VERSION' would technically be possible; nevertheless
--   the dedicated immutable store is chosen here to keep DMS boundaries clean.
-- * Version statuses are limited to the explicit lifecycle from the TZ
--   (DRAFT -> APPROVED -> ACTIVE -> REPLACED, plus CANCELLED).
-- * Document card statuses include ON_APPROVAL/ARCHIVED/REJECTED as listed in TZ 11.3.
-- * No approval workflow/route tables are created here — those belong to APPR-001.

-- ---------------------------------------------------------------------------
-- Immutable file storage for document versions.
-- ---------------------------------------------------------------------------
CREATE TABLE document_files
(
    id           BIGSERIAL PRIMARY KEY,
    storage_key  VARCHAR(1000) NOT NULL UNIQUE,
    file_name    VARCHAR(500)  NOT NULL,
    mime_type    VARCHAR(255),
    size_bytes   BIGINT,
    sha256       VARCHAR(64),
    uploaded_by  BIGINT        NOT NULL REFERENCES users (id),
    uploaded_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_files_sha256 ON document_files (sha256) WHERE sha256 IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Persistent document cards.
-- ---------------------------------------------------------------------------
CREATE TABLE documents
(
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(64)   NOT NULL UNIQUE,
    name            VARCHAR(500)  NOT NULL,
    doc_type        VARCHAR(64)   NOT NULL CHECK (doc_type IN (
        'TECHNICAL_SPECIFICATION', 'CONTRACT', 'INVOICE', 'COMMERCIAL_OFFER',
        'ORDER', 'WAYBILL', 'ACT', 'PRODUCT_PASSPORT', 'MANUAL', 'DRAWING',
        'SPECIFICATION', 'TEST_PROTOCOL', 'MEMO', 'ORDER_DOC', 'REPORT',
        'CERTIFICATE', 'PHOTO', 'OTHER'
    )),
    description     TEXT,
    author_id       BIGINT REFERENCES users (id),
    owner_id        BIGINT        NOT NULL REFERENCES users (id),
    status          VARCHAR(32)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'ON_APPROVAL', 'APPROVED', 'ACTIVE', 'REPLACED', 'ARCHIVED', 'REJECTED'
    )),
    confidentiality VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL' CHECK (confidentiality IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET'
    )),
    retention_until DATE,
    current_version_id BIGINT,
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_documents_number ON documents (number);
CREATE INDEX idx_documents_type ON documents (doc_type);
CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_documents_owner ON documents (owner_id);

-- ---------------------------------------------------------------------------
-- Independent document versions.
-- ---------------------------------------------------------------------------
CREATE TABLE document_versions
(
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT        NOT NULL REFERENCES documents (id),
    version_number  INTEGER       NOT NULL CHECK (version_number > 0),
    status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'APPROVED', 'ACTIVE', 'REPLACED', 'CANCELLED'
    )),
    change_reason   TEXT,
    file_id         BIGINT REFERENCES document_files (id),
    file_name       VARCHAR(500)  NOT NULL,
    mime_type       VARCHAR(255),
    size_bytes      BIGINT,
    sha256          VARCHAR(64),
    approved_by     BIGINT REFERENCES users (id),
    approved_at     TIMESTAMPTZ,
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_document_versions_document_number UNIQUE (document_id, version_number),
    CONSTRAINT uq_document_versions_id_document UNIQUE (id, document_id)
);

CREATE INDEX idx_document_versions_document ON document_versions (document_id, status);
CREATE INDEX idx_document_versions_status ON document_versions (status);

-- Composite FK ensuring the current version belongs to the same document card.
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version
        FOREIGN KEY (current_version_id, id) REFERENCES document_versions (id, document_id);

-- ---------------------------------------------------------------------------
-- Document links to existing PROJECT/TASK/PRODUCT_MODEL/BOM_REVISION/DEMAND.
-- Only tables that already exist in V2/V3/V10 are referenced.
-- ---------------------------------------------------------------------------
CREATE TABLE document_links
(
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT      NOT NULL REFERENCES documents (id),
    project_id        BIGINT REFERENCES projects (id),
    task_id           BIGINT REFERENCES tasks (id),
    product_model_id  BIGINT REFERENCES product_models (id),
    bom_revision_id   BIGINT REFERENCES product_revisions (id),
    demand_id         BIGINT REFERENCES demands (id),
    created_by        BIGINT      NOT NULL REFERENCES users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_document_links_one_target CHECK (
        ((project_id IS NOT NULL)::int + (task_id IS NOT NULL)::int +
         (product_model_id IS NOT NULL)::int + (bom_revision_id IS NOT NULL)::int +
         (demand_id IS NOT NULL)::int) = 1
    )
);

CREATE UNIQUE INDEX uq_document_links_document_target ON document_links (
    document_id,
    COALESCE(project_id, 0),
    COALESCE(task_id, 0),
    COALESCE(product_model_id, 0),
    COALESCE(bom_revision_id, 0),
    COALESCE(demand_id, 0)
);

CREATE INDEX idx_document_links_project ON document_links (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_document_links_task ON document_links (task_id) WHERE task_id IS NOT NULL;
CREATE INDEX idx_document_links_product_model ON document_links (product_model_id) WHERE product_model_id IS NOT NULL;
CREATE INDEX idx_document_links_bom_revision ON document_links (bom_revision_id) WHERE bom_revision_id IS NOT NULL;
CREATE INDEX idx_document_links_demand ON document_links (demand_id) WHERE demand_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Append-only version/action history.
-- ---------------------------------------------------------------------------
CREATE TABLE document_version_history
(
    id          BIGSERIAL PRIMARY KEY,
    version_id  BIGINT      NOT NULL REFERENCES document_versions (id),
    action      VARCHAR(32) NOT NULL CHECK (action IN (
        'CREATED', 'STATUS_CHANGED', 'APPROVED', 'ACTIVATED', 'REPLACED',
        'CANCELLED', 'FILE_REPLACED'
    )),
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    reason      TEXT,
    changed_by  BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_version_history_version ON document_version_history (version_id, created_at);

-- ---------------------------------------------------------------------------
-- Download audit trail (populated by the server on each download).
-- ---------------------------------------------------------------------------
CREATE TABLE document_download_log
(
    id            BIGSERIAL PRIMARY KEY,
    version_id    BIGINT        NOT NULL REFERENCES document_versions (id),
    user_id       BIGINT        NOT NULL REFERENCES users (id),
    ip_address    VARCHAR(64),
    user_agent    TEXT,
    downloaded_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_download_log_version ON document_download_log (version_id, downloaded_at);
CREATE INDEX idx_document_download_log_user ON document_download_log (user_id, downloaded_at);

-- ---------------------------------------------------------------------------
-- Immutable guards.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dms_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '%: % is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- File storage is append-only / immutable.
CREATE TRIGGER trg_document_files_immutable
    BEFORE UPDATE OR DELETE ON document_files
    FOR EACH ROW EXECUTE FUNCTION dms_forbid_mutation();

-- Version history and download log are append-only.
CREATE TRIGGER trg_document_version_history_immutable
    BEFORE UPDATE OR DELETE ON document_version_history
    FOR EACH ROW EXECUTE FUNCTION dms_forbid_mutation();

CREATE TRIGGER trg_document_download_log_immutable
    BEFORE UPDATE OR DELETE ON document_download_log
    FOR EACH ROW EXECUTE FUNCTION dms_forbid_mutation();

-- Approved/active/replaced versions are immutable in content.
CREATE OR REPLACE FUNCTION dms_guard_approved_version() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status NOT IN ('DRAFT', 'CANCELLED') THEN
            RAISE EXCEPTION 'DELETE: only DRAFT or CANCELLED document versions can be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status NOT IN ('DRAFT', 'CANCELLED') THEN
        -- Allow only lifecycle status transitions.
        IF NEW.status <> OLD.status THEN
            IF NOT (
                (OLD.status = 'APPROVED' AND NEW.status = 'ACTIVE') OR
                (OLD.status = 'ACTIVE' AND NEW.status = 'REPLACED')
            ) THEN
                RAISE EXCEPTION 'UPDATE: illegal status transition from % to % for an approved document version', OLD.status, NEW.status;
            END IF;
        END IF;

        -- Protect content fields.
        IF NEW.document_id <> OLD.document_id
            OR NEW.version_number <> OLD.version_number
            OR NEW.file_id IS DISTINCT FROM OLD.file_id
            OR NEW.file_name <> OLD.file_name
            OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
            OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
            OR NEW.sha256 IS DISTINCT FROM OLD.sha256
            OR NEW.created_by <> OLD.created_by
            OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'UPDATE: approved document version content is immutable';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_versions_approved_guard
    BEFORE UPDATE OR DELETE ON document_versions
    FOR EACH ROW EXECUTE FUNCTION dms_guard_approved_version();
