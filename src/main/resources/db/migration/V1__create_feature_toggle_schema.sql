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
    boolean_value BOOLEAN,
    enum_value    VARCHAR(255),
    description   VARCHAR(2000) NOT NULL DEFAULT '',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_feature_group_namespace FOREIGN KEY (group_id, namespace_id)
        REFERENCES feature_group (id, namespace_id) ON DELETE CASCADE,
    CONSTRAINT ck_feature_key CHECK (key ~ '^[A-Za-z]([A-Za-z0-9]|[.-][A-Za-z0-9])*$'),
    CONSTRAINT ck_feature_type CHECK (type IN ('BOOLEAN', 'ENUM')),
    CONSTRAINT ck_feature_typed_value CHECK (
        (type = 'BOOLEAN' AND boolean_value IS NOT NULL AND enum_value IS NULL)
            OR
        (type = 'ENUM' AND boolean_value IS NULL AND enum_value IS NOT NULL)
        )
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

CREATE TABLE feature_enum_option
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_id UUID         NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    value      VARCHAR(255) NOT NULL,
    sort_order INTEGER      NOT NULL,
    CONSTRAINT uq_feature_enum_option_value UNIQUE (feature_id, value),
    CONSTRAINT uq_feature_enum_option_order UNIQUE (feature_id, sort_order),
    CONSTRAINT ck_feature_enum_option_value CHECK (value <> ''),
    CONSTRAINT ck_feature_enum_option_order CHECK (sort_order >= 0)
);

CREATE TABLE feature_audit_log
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id   UUID         NOT NULL REFERENCES namespace (id) ON DELETE CASCADE,
    feature_id  UUID         NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    namespace_key  VARCHAR(255) NOT NULL,
    feature_group VARCHAR(255),
    feature_key VARCHAR(255) NOT NULL,
    operation   VARCHAR(32)  NOT NULL,
    old_value   VARCHAR(255),
    new_value   VARCHAR(255),
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
