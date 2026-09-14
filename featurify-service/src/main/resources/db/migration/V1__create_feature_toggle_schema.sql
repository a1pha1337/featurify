CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE namespace
(
    id             UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    key            VARCHAR(255) NOT NULL,
    display_name   VARCHAR(255) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    default_namespace BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_namespace_key UNIQUE (key),
    CONSTRAINT ck_namespace_key CHECK (key ~ '^[A-Za-z]([A-Za-z0-9]|[.-][A-Za-z0-9])*$'),
    CONSTRAINT ck_system_default_namespace CHECK (
        (default_namespace AND key = 'default' AND display_name = 'Default' AND active)
        OR (NOT default_namespace AND key <> 'default')
    )
);

CREATE UNIQUE INDEX uq_namespace_default
    ON namespace (default_namespace)
    WHERE default_namespace;

INSERT INTO namespace (key, display_name, active, created_at, updated_at, default_namespace)
VALUES ('default', 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE);

CREATE FUNCTION protect_default_namespace()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    RAISE EXCEPTION 'The system Default namespace cannot be changed or deleted'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_protect_default_namespace
    BEFORE UPDATE OR DELETE ON namespace
    FOR EACH ROW WHEN (OLD.default_namespace)
EXECUTE FUNCTION protect_default_namespace();

CREATE TRIGGER trg_protect_default_namespace_truncate
    BEFORE TRUNCATE ON namespace
    FOR EACH STATEMENT
EXECUTE FUNCTION protect_default_namespace();

CREATE TABLE namespace_access_token
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id UUID         NOT NULL REFERENCES namespace (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    token_hash   VARCHAR(60)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_access_token_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_access_token_hash CHECK (length(token_hash) = 60)
);
CREATE INDEX ix_access_token_namespace ON namespace_access_token (namespace_id, created_at DESC);

CREATE TABLE feature_group
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id    UUID         NOT NULL REFERENCES namespace (id) ON DELETE CASCADE,
    key          VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_feature_group_namespace_key UNIQUE (namespace_id, key),
    CONSTRAINT uq_feature_group_id_namespace UNIQUE (id, namespace_id),
    CONSTRAINT ck_feature_group_key CHECK (key ~ '^[A-Za-z]([A-Za-z0-9]|[.-][A-Za-z0-9])*$')
);

CREATE INDEX ix_feature_group_namespace_key ON feature_group (namespace_id, key);

CREATE TABLE feature
(
    id            UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    namespace_id     UUID          NOT NULL REFERENCES namespace (id) ON DELETE CASCADE,
    group_id      UUID,
    key           VARCHAR(255)  NOT NULL,
    type          VARCHAR(16)   NOT NULL,
    description   VARCHAR(2000) NOT NULL DEFAULT '',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_feature_group_namespace FOREIGN KEY (group_id, namespace_id)
        REFERENCES feature_group (id, namespace_id) ON DELETE CASCADE,
    CONSTRAINT ck_feature_key CHECK (key ~ '^[A-Za-z]([A-Za-z0-9]|[.-][A-Za-z0-9])*$'),
    CONSTRAINT ck_feature_type CHECK (type IN ('BOOLEAN', 'ENUM', 'VECTOR')),
    CONSTRAINT uq_feature_id_type UNIQUE (id, type)
);

CREATE UNIQUE INDEX uq_feature_namespace_group_key
    ON feature (namespace_id, group_id, key)
    WHERE group_id IS NOT NULL;

CREATE UNIQUE INDEX uq_feature_namespace_global_key
    ON feature (namespace_id, key)
    WHERE group_id IS NULL;

CREATE INDEX ix_feature_namespace_key ON feature (namespace_id, group_id, key);

CREATE INDEX ix_feature_key_trgm
    ON feature USING GIN (key gin_trgm_ops);

CREATE TABLE feature_boolean_value
(
    feature_id UUID PRIMARY KEY,
    feature_type VARCHAR(16) GENERATED ALWAYS AS ('BOOLEAN') STORED,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT fk_boolean_feature_type FOREIGN KEY (feature_id, feature_type)
        REFERENCES feature (id, type) ON DELETE CASCADE
);

CREATE TABLE feature_enum_option
(
    feature_id UUID NOT NULL,
    feature_type VARCHAR(16) GENERATED ALWAYS AS ('ENUM') STORED,
    value VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL,
    PRIMARY KEY (feature_id, value),
    CONSTRAINT fk_enum_option_feature_type FOREIGN KEY (feature_id, feature_type)
        REFERENCES feature (id, type) ON DELETE CASCADE,
    CONSTRAINT uq_feature_enum_option_order UNIQUE (feature_id, sort_order),
    CONSTRAINT ck_feature_enum_option_value CHECK (length(trim(value)) > 0),
    CONSTRAINT ck_feature_enum_option_order CHECK (sort_order BETWEEN 0 AND 99)
);

CREATE TABLE feature_enum_value
(
    feature_id UUID PRIMARY KEY,
    feature_type VARCHAR(16) GENERATED ALWAYS AS ('ENUM') STORED,
    value VARCHAR(255) NOT NULL,
    CONSTRAINT fk_enum_feature_type FOREIGN KEY (feature_id, feature_type)
        REFERENCES feature (id, type) ON DELETE CASCADE,
    CONSTRAINT fk_enum_selected_option FOREIGN KEY (feature_id, value)
        REFERENCES feature_enum_option (feature_id, value) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE feature_vector_element
(
    feature_id UUID NOT NULL,
    feature_type VARCHAR(16) GENERATED ALWAYS AS ('VECTOR') STORED,
    element VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (feature_id, element),
    CONSTRAINT fk_vector_feature_type FOREIGN KEY (feature_id, feature_type)
        REFERENCES feature (id, type) ON DELETE CASCADE,
    CONSTRAINT ck_vector_element_name CHECK (length(trim(element)) > 0 AND element = trim(element))
);

-- JDBC writes the parent and its children in separate statements. Enforce complete
-- typed values at transaction end, allowing atomic replacement of child rows.
CREATE FUNCTION check_feature_value(target_id UUID)
    RETURNS VOID
    LANGUAGE plpgsql
AS
$$
DECLARE
    target_type VARCHAR(16);
    element_count INTEGER;
BEGIN
    SELECT type INTO target_type FROM feature WHERE id = target_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN; -- Parent deletion cascades to all value tables.
    END IF;
    IF target_type = 'BOOLEAN' AND NOT EXISTS (SELECT 1 FROM feature_boolean_value WHERE feature_id = target_id) THEN
        RAISE EXCEPTION 'BOOLEAN feature requires a value' USING ERRCODE = '23514';
    ELSIF target_type = 'ENUM' AND NOT EXISTS (SELECT 1 FROM feature_enum_value WHERE feature_id = target_id) THEN
        RAISE EXCEPTION 'ENUM feature requires a selected value' USING ERRCODE = '23514';
    ELSIF target_type = 'VECTOR' THEN
        SELECT count(*) INTO element_count FROM feature_vector_element WHERE feature_id = target_id;
        IF element_count NOT BETWEEN 1 AND 100 THEN
            RAISE EXCEPTION 'VECTOR feature requires between 1 and 100 elements' USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION enforce_feature_value()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    id_field TEXT := CASE WHEN TG_TABLE_NAME = 'feature' THEN 'id' ELSE 'feature_id' END;
    old_id UUID;
    new_id UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_id := (to_jsonb(OLD) ->> id_field)::UUID;
        PERFORM check_feature_value(old_id);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_id := (to_jsonb(NEW) ->> id_field)::UUID;
        IF new_id IS DISTINCT FROM old_id THEN
            PERFORM check_feature_value(new_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_feature_value_required
    AFTER INSERT OR UPDATE OR DELETE ON feature DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_feature_value();
CREATE CONSTRAINT TRIGGER trg_boolean_value_required
    AFTER INSERT OR UPDATE OR DELETE ON feature_boolean_value DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_feature_value();
CREATE CONSTRAINT TRIGGER trg_enum_value_required
    AFTER INSERT OR UPDATE OR DELETE ON feature_enum_value DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_feature_value();
CREATE CONSTRAINT TRIGGER trg_vector_value_required
    AFTER INSERT OR UPDATE OR DELETE ON feature_vector_element DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_feature_value();

CREATE TABLE feature_audit_log
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id   UUID         NOT NULL REFERENCES namespace (id) ON DELETE CASCADE,
    feature_id  UUID         NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    namespace_key  VARCHAR(255) NOT NULL,
    feature_group VARCHAR(255),
    feature_key VARCHAR(255) NOT NULL,
    operation   VARCHAR(32)  NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    changed_by  VARCHAR(255) NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_feature_audit_operation CHECK (operation IN ('VALUE_CHANGED'))
);

CREATE INDEX ix_feature_audit_feature_id ON feature_audit_log (feature_id);

CREATE INDEX ix_feature_audit_lookup
    ON feature_audit_log (namespace_id, feature_group, feature_key, changed_at DESC);

CREATE FUNCTION prevent_feature_type_change()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NEW.type <> OLD.type THEN
        RAISE EXCEPTION 'feature type is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_feature_type_immutable
    BEFORE UPDATE OF type
    ON feature
    FOR EACH ROW
EXECUTE FUNCTION prevent_feature_type_change();

-- Ownership and the last accepted generation survive operator restarts. Released rows
-- are tombstones: delayed requests from a deleted CR must never recreate its data.
CREATE TABLE namespace_manifest (
    id UUID PRIMARY KEY,
    namespace_key VARCHAR(255) NOT NULL,
    namespace_id UUID REFERENCES namespace(id) ON DELETE SET NULL,
    owner JSONB NOT NULL,
    principal VARCHAR(255) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    spec JSONB,
    managed_groups JSONB NOT NULL DEFAULT '[]',
    managed_features JSONB NOT NULL DEFAULT '[]',
    released BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_namespace_manifest_active ON namespace_manifest(namespace_key) WHERE NOT released;
CREATE TABLE namespace_manifest_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    binding_id UUID NOT NULL REFERENCES namespace_manifest(id),
    generation BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
