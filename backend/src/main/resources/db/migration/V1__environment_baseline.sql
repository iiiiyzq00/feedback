-- Verify MySQL 8.4 LTS settings before applying schema migrations.
-- This script is intentionally non-destructive and does not create the database.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;
SET time_zone = '+00:00';
SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION');
SELECT VERSION() AS mysql_version, @@time_zone AS session_time_zone, @@collation_connection AS collation_connection, @@sql_mode AS sql_mode;
