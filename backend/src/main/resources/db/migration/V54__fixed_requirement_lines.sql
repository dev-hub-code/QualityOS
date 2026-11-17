-- A recipe line can be a fixed quantity whatever the batch size. A work order's line now keeps that, so what a receipt
-- is compared with does not scale a fixed line with the quantity made.
ALTER TABLE work_order_materials ADD COLUMN scales boolean NOT NULL DEFAULT true;

-- Orders already released: their lines were written one per BOM or recipe line, numbered in line order.
UPDATE work_order_materials m SET scales = false
FROM work_orders o,
     (SELECT bom_id, scales, row_number() OVER (PARTITION BY bom_id ORDER BY line_number) AS n FROM bom_lines) l
WHERE m.work_order_id = o.id AND l.bom_id = o.bom_id AND l.n = m.line_number AND NOT l.scales;

-- Frozen on release, like the rest of the line.
CREATE OR REPLACE FUNCTION protect_released_alternates() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.alternate_material_ids IS DISTINCT FROM OLD.alternate_material_ids OR NEW.scales IS DISTINCT FROM OLD.scales THEN
        RAISE EXCEPTION 'work_order_materials % was released and cannot be rewritten', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
