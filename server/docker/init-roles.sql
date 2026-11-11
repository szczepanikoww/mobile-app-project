-- Local development only (docker-compose). Two roles, as database-design §4 requires:
--   trainer_owner owns the schema and runs Flyway; trainer_app is the API's DML-only role.
-- btree_gist, citext and pg_trgm are trusted extensions, so the database owner can create them.
CREATE ROLE trainer_owner LOGIN PASSWORD 'trainer_owner';
CREATE ROLE trainer_app LOGIN PASSWORD 'trainer_app';
CREATE DATABASE trainer OWNER trainer_owner;
