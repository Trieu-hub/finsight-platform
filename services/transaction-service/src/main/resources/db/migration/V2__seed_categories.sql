-- Default categories seeded so categoryId validation has a source of truth.
-- INSERT IGNORE keeps the seed idempotent and safe to re-run against an existing DB.
INSERT IGNORE INTO categories (id, name, type) VALUES
    (1, 'Salary',        'INCOME'),
    (2, 'Investment',    'INCOME'),
    (3, 'Refund',        'INCOME'),
    (4, 'Food & Dining', 'EXPENSE'),
    (5, 'Transport',     'EXPENSE'),
    (6, 'Housing',       'EXPENSE'),
    (7, 'Utilities',     'EXPENSE'),
    (8, 'Entertainment', 'EXPENSE'),
    (9, 'Healthcare',    'EXPENSE'),
    (10, 'Other',        'EXPENSE');
