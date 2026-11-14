-- V21: Production test acceptance — separate technical decision from test execution.
--
-- Review findings addressed:
-- * prod_guard_test_mutation allowed DELETE; now DELETE is forbidden.
-- * No separate acceptance decision by an authorized technical reviewer;
--   added technical_decision / technical_decision_by / technical_decision_at.
-- * supersedes_test_id could receive multiple competing corrections;
--   added a partial UNIQUE index so each superseded test has at most one successor.

-- ---------------------------------------------------------------------------
-- Add separate technical decision columns to instance_tests.
-- ---------------------------------------------------------------------------
ALTER TABLE instance_tests
    ADD COLUMN technical_decision VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (technical_decision IN ('PENDING', 'GOOD', 'NOT_GOOD')),
    ADD COLUMN technical_decision_by BIGINT REFERENCES users (id),
    ADD COLUMN technical_decision_at TIMESTAMPTZ;

-- Consistency: PENDING requires no decision metadata; GOOD/NOT_GOOD require both.
ALTER TABLE instance_tests
    ADD CONSTRAINT chk_instance_tests_technical_decision_consistency CHECK (
        (technical_decision = 'PENDING' AND technical_decision_by IS NULL AND technical_decision_at IS NULL)
        OR
        (technical_decision IN ('GOOD', 'NOT_GOOD') AND technical_decision_by IS NOT NULL AND technical_decision_at IS NOT NULL)
    );

-- ---------------------------------------------------------------------------
-- At most one test can supersede a given previous test.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_instance_tests_supersedes;

CREATE UNIQUE INDEX uq_instance_tests_supersedes
    ON instance_tests (supersedes_test_id)
    WHERE supersedes_test_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Replace the test mutation guard.
-- DELETE is forbidden; old content stays immutable; technical decision is
-- a one-time PENDING -> GOOD/NOT_GOOD transition; version may increment.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prod_guard_test_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: instance test cannot be deleted';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Immutable content (except version and the one-time technical decision).
        IF NEW.instance_id <> OLD.instance_id
            OR NEW.order_id IS DISTINCT FROM OLD.order_id
            OR NEW.protocol_document_version_id IS DISTINCT FROM OLD.protocol_document_version_id
            OR NEW.performed_by <> OLD.performed_by
            OR NEW.performed_at <> OLD.performed_at
            OR NEW.result <> OLD.result
            OR NEW.notes IS DISTINCT FROM OLD.notes
            OR NEW.supersedes_test_id IS DISTINCT FROM OLD.supersedes_test_id THEN
            RAISE EXCEPTION 'UPDATE: instance test content is immutable';
        END IF;

        -- Technical decision can only move PENDING -> GOOD/NOT_GOOD once.
        IF NEW.technical_decision <> OLD.technical_decision
            OR NEW.technical_decision_by IS DISTINCT FROM OLD.technical_decision_by
            OR NEW.technical_decision_at IS DISTINCT FROM OLD.technical_decision_at THEN

            IF OLD.technical_decision <> 'PENDING' THEN
                RAISE EXCEPTION 'UPDATE: technical decision is already made';
            END IF;

            IF NEW.technical_decision NOT IN ('GOOD', 'NOT_GOOD') THEN
                RAISE EXCEPTION 'UPDATE: technical decision can only be GOOD or NOT_GOOD';
            END IF;

            IF NEW.technical_decision_by IS NULL OR NEW.technical_decision_at IS NULL THEN
                RAISE EXCEPTION 'UPDATE: technical decision requires user and timestamp';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
