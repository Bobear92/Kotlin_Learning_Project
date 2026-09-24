-- Study notes: docs/learning/notes/main/resources/db/migration/V1__create_inventory.sql.md

CREATE TABLE inventory (
    -- Why: the SKU is the natural key; a surrogate id column would never be used by the API.
    -- Why: TEXT because the contract sets no max length; TEXT also compares exactly (case-sensitive).
    sku_id TEXT PRIMARY KEY,
    -- Why: the database rejects negative stock even if application code has a bug (oversell backstop).
    quantity INT NOT NULL CHECK (quantity >= 0)
);