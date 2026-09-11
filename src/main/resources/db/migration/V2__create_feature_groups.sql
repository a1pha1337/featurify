-- V1 is immutable: upgrade existing group_key values without losing features.
CREATE TABLE feature_group
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),
    key          VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_feature_group_tenant_key UNIQUE (tenant_id, key),
    CONSTRAINT uq_feature_group_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_feature_group_key CHECK (key ~ '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$'),
    CONSTRAINT ck_feature_group_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX ix_feature_group_tenant_status_key ON feature_group (tenant_id, status, key);


INSERT INTO feature_group (tenant_id, key, display_name, created_at, updated_at)
SELECT tenant_id, group_key, group_key, MIN(created_at), MAX(updated_at)
FROM feature
WHERE group_key IS NOT NULL
GROUP BY tenant_id, group_key;

ALTER TABLE feature ADD COLUMN group_id UUID;
UPDATE feature f
SET group_id = g.id
FROM feature_group g
WHERE f.tenant_id = g.tenant_id AND f.group_key = g.key;

DROP INDEX uq_feature_tenant_group_key;
DROP INDEX uq_feature_tenant_global_key;
DROP INDEX ix_feature_tenant_status_key;
ALTER TABLE feature DROP CONSTRAINT ck_feature_group_key;
ALTER TABLE feature DROP COLUMN group_key;
ALTER TABLE feature ADD CONSTRAINT fk_feature_group_tenant
    FOREIGN KEY (group_id, tenant_id) REFERENCES feature_group (id, tenant_id);

CREATE UNIQUE INDEX uq_feature_tenant_group_key
    ON feature (tenant_id, group_id, key) WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_feature_tenant_global_key
    ON feature (tenant_id, key) WHERE group_id IS NULL;
CREATE INDEX ix_feature_tenant_status_key ON feature (tenant_id, status, group_id, key);
