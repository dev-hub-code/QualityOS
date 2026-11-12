-- Quantities can be entered in any unit of the same kind as the stock they draw on: grams from a lot received in
-- kilograms, millimetres from a batch counted in metres. Each unit says how many of its dimension's base unit it
-- holds. A unit without a factor converts only to itself.

ALTER TABLE units_of_measure
    ADD COLUMN to_base numeric(24,12),
    ADD CONSTRAINT ck_units_to_base CHECK (to_base IS NULL OR to_base > 0);

-- The shared units have no tenant, so no policy lets even the owner change them while row security is forced.
ALTER TABLE units_of_measure NO FORCE ROW LEVEL SECURITY;
UPDATE units_of_measure u SET to_base = f.to_base
FROM (VALUES ('MM', 0.001), ('CM', 0.01), ('M', 1),
             ('GM', 0.001), ('KG', 1),
             ('ML', 0.001), ('LTR', 1),
             ('PCS', 1)) AS f(code, to_base)
WHERE u.tenant_id IS NULL AND u.code = f.code;
ALTER TABLE units_of_measure FORCE ROW LEVEL SECURITY;

-- What was typed, kept beside the quantity taken from the source in the source's own unit.
ALTER TABLE batch_relationships
    ADD COLUMN entered_quantity numeric(18,6),
    ADD COLUMN entered_uom_id uuid REFERENCES units_of_measure (id),
    ADD CONSTRAINT ck_batch_rel_entered CHECK (
        (entered_quantity IS NULL AND entered_uom_id IS NULL)
        OR (entered_quantity > 0 AND entered_uom_id IS NOT NULL));

-- The unit of an NCR's rejected quantity. NCRs raised before this keep a bare quantity: closed ones cannot change.
ALTER TABLE non_conformances
    ADD COLUMN rejected_uom_id uuid REFERENCES units_of_measure (id);


-- A quantity in another unit of the same kind, for reports. Null when the two cannot be converted. Runs as the
-- caller, so it sees only the units the caller can.
CREATE FUNCTION convert_quantity(p_quantity numeric, p_from uuid, p_to uuid) RETURNS numeric
    LANGUAGE sql STABLE
    SET search_path TO 'public'
    AS $$
    SELECT CASE WHEN p_from = p_to THEN p_quantity
                ELSE (SELECT p_quantity * f.to_base / t.to_base
                      FROM units_of_measure f, units_of_measure t
                      WHERE f.id = p_from AND t.id = p_to AND lower(f.dimension) = lower(t.dimension)) END;
$$;

REVOKE ALL ON FUNCTION convert_quantity(numeric, uuid, uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION convert_quantity(numeric, uuid, uuid) TO ${approle};
