-- Add a default 'Snacks' expense category so spending like coffee/street-food can sit in its
-- own budget, separate from 'Food & Dining' — one budget per category keeps the budget tally
-- unambiguous. Id 15 is the next free id (14 is V9's Shopping). Marked is_system like the other
-- defaults. INSERT IGNORE keeps the seed idempotent.
INSERT IGNORE INTO categories (id, name, type, is_system) VALUES
    (15, 'Snacks', 'EXPENSE', TRUE);
