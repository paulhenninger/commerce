-- The two logins of one Commerce database, each with no more than it needs.
-- Run in that database; the logins are named after it:
--
--   <db>_owner  may change the schema of this one database, nothing else on the
--               server. A service signs in as it only to run Flyway migrations at
--               startup.
--   <db>_app    everything else: read, add, change and delete rows in this
--               database. It cannot create, alter, drop or truncate a table, see
--               another database, or manage logins, so a flaw in a service (SQL
--               injection, say) reaches the rows that service already handles
--               and no further — never another service's data.
--
-- Neither is an administrator. Run as one, at any time: it is idempotent and takes
-- back anything granted to either login by hand. Passwords are optional; leave one
-- NULL and it stays as it is. A login that has never had a password is locked.
-- Both logins must connect over TLS.
--
--   mysql -u root -p orders -e "SET @owner_password='...', @app_password='...'; SOURCE deploy/mysql/roles.sql"
--
-- deploy/compose.yml runs it for every database on every start (the mysql-init
-- service, through apply-roles.sh). Every statement is a plain or prepared
-- statement ending in a semicolon at the end of a line: the identity service's
-- migration test executes this file statement by statement.

SET @db = DATABASE();
SET @owner = CONCAT('''', @db, '_owner''@''%''');
SET @app = CONCAT('''', @db, '_app''@''%''');

SET @sql = CONCAT('CREATE USER IF NOT EXISTS ', @owner, ' ACCOUNT LOCK');
PREPARE statement FROM @sql;
EXECUTE statement;
SET @sql = CONCAT('CREATE USER IF NOT EXISTS ', @app, ' ACCOUNT LOCK');
PREPARE statement FROM @sql;
EXECUTE statement;

-- Start from nothing, so a right added by hand does not survive.
SET @sql = CONCAT('REVOKE ALL PRIVILEGES, GRANT OPTION FROM ', @owner, ', ', @app);
PREPARE statement FROM @sql;
EXECUTE statement;
SET @sql = CONCAT('SET DEFAULT ROLE NONE TO ', @owner, ', ', @app);
PREPARE statement FROM @sql;
EXECUTE statement;
SET @sql = CONCAT('ALTER USER ', @owner, ', ', @app, ' REQUIRE SSL');
PREPARE statement FROM @sql;
EXECUTE statement;

SET @sql = CONCAT('GRANT ALL PRIVILEGES ON `', REPLACE(@db, '`', '``'), '`.* TO ', @owner);
PREPARE statement FROM @sql;
EXECUTE statement;
SET @sql = CONCAT('GRANT SELECT, INSERT, UPDATE, DELETE ON `', REPLACE(@db, '`', '``'), '`.* TO ', @app);
PREPARE statement FROM @sql;
EXECUTE statement;

SET @sql = IF(@owner_password IS NULL, 'DO 0',
    CONCAT('ALTER USER ', @owner, ' IDENTIFIED BY ', QUOTE(@owner_password), ' ACCOUNT UNLOCK'));
PREPARE statement FROM @sql;
EXECUTE statement;
SET @sql = IF(@app_password IS NULL, 'DO 0',
    CONCAT('ALTER USER ', @app, ' IDENTIFIED BY ', QUOTE(@app_password), ' ACCOUNT UNLOCK'));
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
SET @sql = NULL, @owner_password = NULL, @app_password = NULL;
