-- A BOM or recipe line can name approved substitutes for its material. They are frozen into a work order on release,
-- like the rest of the line, and taken against the line one for one in its unit.
ALTER TABLE bom_lines ADD COLUMN alternate_material_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE work_order_materials ADD COLUMN alternate_material_ids jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE FUNCTION protect_released_alternates() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.alternate_material_ids IS DISTINCT FROM OLD.alternate_material_ids THEN
        RAISE EXCEPTION 'work_order_materials % was released and cannot be rewritten', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_alternates BEFORE UPDATE ON work_order_materials
    FOR EACH ROW EXECUTE FUNCTION protect_released_alternates();
