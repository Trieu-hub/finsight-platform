-- ROLE_GAMER: grants access to the LuckyMe mini-games section. Everyone but GAMER
-- and ADMIN is denied LuckyMe (gated in the game API and the UI).
INSERT IGNORE INTO roles (name)
VALUES ('ROLE_GAMER');
