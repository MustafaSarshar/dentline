-- Runs once when the Postgres volume is first created. The booking database is created by
-- POSTGRES_DB; each service owns its own database so neither can read the other's tables.
create database notification owner dentline;
