CREATE FUNCTION prevent_feature_type_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type <> OLD.type THEN
        RAISE EXCEPTION 'feature type is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_feature_type_immutable
    BEFORE UPDATE OF type ON feature
    FOR EACH ROW
    EXECUTE FUNCTION prevent_feature_type_change();
