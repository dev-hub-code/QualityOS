-- Lots can expire. A material's shelf life gives each received lot its last day of use unless one is entered;
-- expired lots are not reserved, issued or consumed, and the rest are taken first expiring first.
ALTER TABLE materials
    ADD COLUMN shelf_life_days integer,
    ADD CONSTRAINT ck_materials_shelf_life CHECK (shelf_life_days IS NULL OR shelf_life_days BETWEEN 1 AND 36500);

ALTER TABLE material_receipt_items ADD COLUMN expiry_date date;
