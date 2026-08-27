-- Удалённый эксперимент не должен оставлять книги, квоты и пользовательское
-- состояние в production-базе. Порядок важен из-за внешних ключей.
DROP TABLE IF EXISTS wolfy.research_user_state;
DROP TABLE IF EXISTS wolfy.research_quota_reservations;
DROP TABLE IF EXISTS wolfy.research_quota_charges;
DROP TABLE IF EXISTS wolfy.research_source_chunks;
DROP TABLE IF EXISTS wolfy.research_analyses;
