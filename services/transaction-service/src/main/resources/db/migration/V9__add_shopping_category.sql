-- Add a default 'Shopping' expense category. Ids 1-10 are the V2 seed, 11 Transfer (V5),
-- 12 Games / 13 Winnings (V7); 14 is the next free id. Marked is_system like the other
-- defaults so it cannot be renamed or deleted. INSERT IGNORE keeps the seed idempotent.
INSERT IGNORE INTO categories (id, name, type, is_system) VALUES
    (14, 'Shopping', 'EXPENSE', TRUE);
