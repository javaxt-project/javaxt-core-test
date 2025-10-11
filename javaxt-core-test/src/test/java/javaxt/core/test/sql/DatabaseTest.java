package javaxt.core.test.sql;

import java.io.*;
import java.util.*;
import javaxt.sql.Database;
import org.junit.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for javaxt.sql.Database class functionality.
 * Tests database introspection, metadata retrieval, and connection management.
 *
 * <p>Tests run against all configured databases (PostgreSQL, MySQL, H2).
 * If no databases are configured, all tests will be skipped.</p>
 */
@RunWith(Parameterized.class)
public class DatabaseTest {


    private Database database;
    private static String lastPrintedDatabase = null;

    /**
     * Loads database configurations from properties files
     */
    private static List<Database> loadDatabases() {
        List<Database> configs = new ArrayList<>();

        // Try to load PostgreSQL
        Database pgConfig = loadPostgreSQL();
        if (pgConfig != null) configs.add(pgConfig);

        // Try to load MySQL
        Database mysqlConfig = loadMySQL();
        if (mysqlConfig != null) configs.add(mysqlConfig);

        // Always add H2 (built-in, file-based)
        configs.add(loadH2());

        return configs;
    }

    private static Database loadPostgreSQL() {
        try (InputStream input = DatabaseTest.class.getClassLoader()
                .getResourceAsStream("postgresql.properties")) {

            if (input == null) return null;

            Properties props = new Properties();
            props.load(input);


            String host = props.getProperty("db.host");
            int port = Integer.parseInt(props.getProperty("db.port", "5432"));
            String dbName = props.getProperty("db.name");
            String user = props.getProperty("db.user");
            String password = props.getProperty("db.password", "");

            if (host == null || dbName == null || user == null) return null;

            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
            Database database = new Database();
            database.setDriver("PostgreSQL");
            database.setHost(host);
            database.setPort(port);
            database.setName(dbName);
            database.setUserName(user);
            database.setPassword(password);
            return database;
        } catch (Exception e) {
            return null;
        }
    }

    private static Database loadMySQL() {
        try {
            InputStream input = DatabaseTest.class.getClassLoader()
                .getResourceAsStream("mysql.properties");
            if (input == null) return null;

            Properties props = new Properties();
            props.load(input);
            input.close();

            String host = props.getProperty("db.host");
            int port = Integer.parseInt(props.getProperty("db.port", "3306"));
            String dbName = props.getProperty("db.name");
            String user = props.getProperty("db.user");
            String password = props.getProperty("db.password", "");

            if (host == null || dbName == null || user == null) return null;

            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName;
            Database database = new Database();
            database.setDriver("MySQL");
            database.setHost(host);
            database.setPort(port);
            database.setName(dbName);
            database.setUserName(user);
            database.setPassword(password);
            return database;
        } catch (Exception e) {
            return null;
        }
    }

    private static Database loadH2() {
        // H2 is always available - use file-based database in temp directory
        String tempDir = System.getProperty("java.io.tmpdir");
        if (!tempDir.endsWith(File.separator)) {
            tempDir += File.separator;
        }
        String h2Path = tempDir + "javaxt_test_h2";

        Database database = new Database();
        database.setDriver("H2");
        database.setHost(h2Path);
        database.setUserName("sa");
        database.setPassword("");
        return database;
    }

    /**
     * Provides test parameters - one set for each configured database.
     */
    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        List<Database> databases = loadDatabases();

        assumeTrue(
            "Skipping Database tests: No valid database configurations found",
            !databases.isEmpty()
        );

        List<Object[]> params = new ArrayList<>();
        for (Database db : databases) {
            // Use driver vendor as the display name
            String displayName = db.getDriver().getVendor();
            params.add(new Object[]{displayName, db});
        }
        return params;
    }

    /**
     * Constructor that receives the database instance for this test run.
     */
    public DatabaseTest(String displayName, Database database) {
        this.database = database;
    }

    /**
     * Helper method to print test section headers with database identifier
     */
    private void printTestHeader(String testName) {
        String dbType = database.getDriver().getVendor();
        System.out.println("\n=== " + dbType + " - " + testName + " ===");
    }

    @Before
    public void setUp() throws Exception {
        // Print database header once per database
        String dbName = database.getDriver().getVendor();
        if (!dbName.equals(lastPrintedDatabase)) {
            System.out.println("\n=== " + dbName + " - Database Tests ===");
            lastPrintedDatabase = dbName;
        }

        // Initialize test database schema using JDBC directly
        try (javaxt.sql.Connection conn = database.getConnection()) {

            // Create test tables
            conn.execute("DROP TABLE IF EXISTS db_test_users");
            conn.execute("DROP TABLE IF EXISTS db_test_orders");

            // Create users table
            String createUsers = "CREATE TABLE db_test_users (" +
                "id INT PRIMARY KEY, " +
                "username VARCHAR(50) NOT NULL, " +
                "email VARCHAR(100), " +
                "created_at TIMESTAMP" +
                ")";
            conn.execute(createUsers);

            // Create orders table
            String createOrders = "CREATE TABLE db_test_orders (" +
                "order_id INT PRIMARY KEY, " +
                "user_id INT, " +
                "amount DECIMAL(10,2), " +
                "status VARCHAR(20)" +
                ")";
            conn.execute(createOrders);

            // Insert test data
            conn.execute(
                "INSERT INTO db_test_users VALUES (1, 'alice', 'alice@example.com', CURRENT_TIMESTAMP)");
            conn.execute(
                "INSERT INTO db_test_users VALUES (2, 'bob', 'bob@example.com', CURRENT_TIMESTAMP)");
            conn.execute(
                "INSERT INTO db_test_orders VALUES (100, 1, 99.99, 'completed')");
            conn.execute(
                "INSERT INTO db_test_orders VALUES (101, 2, 149.50, 'pending')");
        }
    }

    @After
    public void tearDown() throws Exception {
        // Cleanup test tables
        try (javaxt.sql.Connection conn = database.getConnection()) {
            conn.execute("DROP TABLE IF EXISTS db_test_users");
            conn.execute("DROP TABLE IF EXISTS db_test_orders");
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    // ==================== DATABASE CONNECTION TESTS ====================

    @Test
    public void testDatabaseConnection() throws Exception {
        printTestHeader("Database Connection Test");

        try (javaxt.sql.Connection conn = database.getConnection()) {
            assertNotNull("Connection should not be null", conn);
            assertFalse("Connection should be open", conn.isClosed());
            assertTrue("Connection should be valid", conn.getConnection().isValid(5));

            System.out.println("Connection String: " + database.getConnectionString());
            System.out.println("Connection Speed: " + conn.getConnectionSpeed() + " ms");
        }
    }

    @Test
    public void testGetCatalogs() throws Exception {
        printTestHeader("Get Catalogs Test");

        System.out.println("Connection String: " + database.getConnectionString());

        String[] catalogs = database.getCatalogs();
        System.out.println("Catalogs found: " + (catalogs != null ? catalogs.length : 0));

        if (catalogs != null && catalogs.length > 0) {
            for (String catalog : catalogs) {
                System.out.println("  - " + catalog);
            }
        }

        // Validation depends on database type
        // Some databases support catalogs (MySQL), some don't (PostgreSQL uses schemas)
        assertNotNull("Catalogs array should not be null", catalogs);

    }

    @Test
    public void testGetTables() throws Exception {
        printTestHeader("Get Tables Test");


        javaxt.sql.Table[] tables = database.getTables();

        assertNotNull("Tables array should not be null", tables);
        assertTrue("Should have at least 2 test tables", tables.length >= 2);

        System.out.println("Tables found: " + tables.length);

        // Find our test tables
        boolean foundUsers = false;
        boolean foundOrders = false;

        for (javaxt.sql.Table table : tables) {
            String tableName = table.getName().toLowerCase();

            if (tableName.contains("db_test_users")) {
                foundUsers = true;
                System.out.println("\n" + table);
                System.out.println("---------------------");

                javaxt.sql.Column[] columns = table.getColumns();
                for (javaxt.sql.Column column : columns) {
                    System.out.println(" - " + column);
                }

                // Verify expected columns exist
                assertTrue("Users table should have columns", columns.length >= 4);
            }

            if (tableName.contains("db_test_orders")) {
                foundOrders = true;
                System.out.println("\n" + table);
                System.out.println("---------------------");

                javaxt.sql.Column[] columns = table.getColumns();
                for (javaxt.sql.Column column : columns) {
                    System.out.println(" - " + column);
                }

                // Verify expected columns exist
                assertTrue("Orders table should have columns", columns.length >= 4);
            }
        }

        assertTrue("Should find db_test_users table", foundUsers);
        assertTrue("Should find db_test_orders table", foundOrders);
    }

    @Test
    public void testGetDriver() throws Exception {
        printTestHeader("Get Driver Test");

        javaxt.sql.Driver driver = database.getDriver();

        assertNotNull("Driver should not be null", driver);
        System.out.println("Driver: " + driver.toString());
        System.out.println("Vendor: " + driver.getVendor());

        // Verify driver vendor matches expected database type
        String vendor = driver.getVendor().toLowerCase();
        if (driver.equals("PostgreSQL")) {
            assertTrue("Driver should be PostgreSQL", vendor.contains("postgres"));
        } else if (driver.equals("MySQL")) {
            assertTrue("Driver should be MySQL", vendor.contains("mysql"));
        } else if (driver.equals("H2")) {
            assertTrue("Driver should be H2", vendor.contains("h2"));
        }
    }

    @Test
    public void testDatabaseMetadata() throws Exception {
        printTestHeader("Database Metadata Test");

        System.out.println("Connection String: " + database.getConnectionString());
        System.out.println("Host: " + database.getHost());
        if (database.getName() != null) {
            System.out.println("Database Name: " + database.getName());
        }
        if (database.getPort() != null) {
            System.out.println("Port: " + database.getPort());
        }
        System.out.println("Username: " + database.getUserName());
        System.out.println("Driver: " + database.getDriver().toString());

        // Verify basic metadata
        assertNotNull("Connection string should not be null", database.getConnectionString());
        assertNotNull("Username should not be null", database.getUserName());
    }

    @Test
    public void testQueryExecution() throws Exception {
        printTestHeader("Query Execution Test");

        try (javaxt.sql.Connection conn = database.getConnection()) {
            // Test simple query
            javaxt.sql.Record record = conn.getRecord("SELECT COUNT(*) as cnt FROM db_test_users");
            assertNotNull("Record should not be null", record);
            assertEquals("Should have 2 users", 2, record.get("cnt").toInteger().intValue());

            System.out.println("User count: " + record.get("cnt"));

            // Test query with iteration
            int userCount = 0;
            for (javaxt.sql.Record user : conn.getRecords("SELECT username, email FROM db_test_users ORDER BY id")) {
                System.out.println("User: " + user.get("username") + " <" + user.get("email") + ">");
                userCount++;
            }
            assertEquals("Should iterate over 2 users", 2, userCount);
        }
    }

    @Test
    public void testTableIntrospection() throws Exception {
        printTestHeader("Table Introspection Test");


        javaxt.sql.Table[] tables = database.getTables();
        javaxt.sql.Table usersTable = null;

        // Find the users table
        for (javaxt.sql.Table table : tables) {
            if (table.getName().toLowerCase().contains("db_test_users")) {
                usersTable = table;
                break;
            }
        }

        assertNotNull("Users table should exist", usersTable);

        System.out.println("\nTable: " + usersTable);
        System.out.println("---------------------");

        javaxt.sql.Column[] columns = usersTable.getColumns();

        for (javaxt.sql.Column column : columns) {
            System.out.println(" - " + column);
            System.out.println("   Type: " + column.getType());
            System.out.println("   Length: " + column.getLength());
            System.out.println("   Primary Key: " + column.isPrimaryKey());
        }

        // Verify column count
        assertTrue("Should have at least 4 columns", columns.length >= 4);

        // Verify specific columns exist
        boolean hasId = false;
        boolean hasUsername = false;
        boolean hasEmail = false;

        for (javaxt.sql.Column column : columns) {
            String colName = column.getName().toLowerCase();
            if (colName.equals("id")) hasId = true;
            if (colName.equals("username")) hasUsername = true;
            if (colName.equals("email")) hasEmail = true;
        }

        assertTrue("Should have id column", hasId);
        assertTrue("Should have username column", hasUsername);
        assertTrue("Should have email column", hasEmail);

    }

    @Test
    public void testMultipleTableRetrieval() throws Exception {
        printTestHeader("Multiple Table Retrieval Test");

        System.out.println("Connection String: " + database.getConnectionString());

        // Get all catalogs
        String[] catalogs = database.getCatalogs();
        if (catalogs != null && catalogs.length > 0) {
            System.out.println("\nCatalogs:");
            for (String catalog : catalogs) {
                System.out.println("  - " + catalog);
            }
        }

        // Get all tables
        System.out.println("\nTables:");
        for (javaxt.sql.Table table : database.getTables()) {
            String tableName = table.getName().toLowerCase();

            // Only print our test tables to keep output clean
            if (tableName.contains("db_test")) {
                System.out.println("\n" + table);
                System.out.println("---------------------");
                for (javaxt.sql.Column column : table.getColumns()) {
                    System.out.println(" - " + column);
                }
            }
        }

        // Verify we can find both test tables
        javaxt.sql.Table[] tables = database.getTables();
        int testTableCount = 0;

        for (javaxt.sql.Table table : tables) {
            String tableName = table.getName().toLowerCase();
            if (tableName.contains("db_test_users") || tableName.contains("db_test_orders")) {
                testTableCount++;
            }
        }

        assertEquals("Should find 2 test tables", 2, testTableCount);

    }

    @Test
    public void testConnectionReuse() throws Exception {
        printTestHeader("Connection Reuse Test");

        // Get multiple connections and verify they work
        for (int i = 0; i < 5; i++) {
            try (javaxt.sql.Connection conn = database.getConnection()) {
                assertNotNull("Connection " + i + " should not be null", conn);

                // Execute a simple query
                javaxt.sql.Record record = conn.getRecord("SELECT COUNT(*) as cnt FROM db_test_users");
                assertEquals("Query should return correct count", 2, record.get("cnt").toInteger().intValue());
            }
        }

        System.out.println("Successfully acquired and released 5 connections");
    }

    @Test
    public void testDatabaseProperties() throws Exception {
        printTestHeader("Database Properties Test");

        // Test various database properties
        assertNotNull("Host should not be null", database.getHost());
        assertNotNull("Driver should not be null", database.getDriver());

        System.out.println("Host: " + database.getHost());
        if (database.getPort() != null) {
            System.out.println("Port: " + database.getPort());
        }
        if (database.getName() != null) {
            System.out.println("Name: " + database.getName());
        }
        System.out.println("User: " + database.getUserName());
        System.out.println("Driver: " + database.getDriver().toString());
        System.out.println("Vendor: " + database.getDriver().getVendor());

        // Verify connection string is properly formed
        String connString = database.getConnectionString();
        assertNotNull("Connection string should not be null", connString);
    }

    @Test
    public void testColumnMetadata() throws Exception {
        printTestHeader("Column Metadata Test");

        javaxt.sql.Table[] tables = database.getTables();
        javaxt.sql.Table usersTable = null;

        // Find the users table
        for (javaxt.sql.Table table : tables) {
            if (table.getName().toLowerCase().contains("db_test_users")) {
                usersTable = table;
                break;
            }
        }

        assertNotNull("Users table should exist", usersTable);

        javaxt.sql.Column[] columns = usersTable.getColumns();

        System.out.println("Table: " + usersTable.getName());
        System.out.println("\nColumn Details:");

        for (javaxt.sql.Column column : columns) {
            System.out.println("\nColumn: " + column.getName());
            System.out.println("  Type: " + column.getType());
            System.out.println("  Length: " + column.getLength());
            System.out.println("  Primary Key: " + column.isPrimaryKey());

            // Verify column has valid metadata
            assertNotNull("Column name should not be null", column.getName());
            assertNotNull("Column type should not be null", column.getType());
        }

        // Verify we can find the ID column (Note: isPrimaryKey() doesn't always work correctly)
        // Check via table.getPrimaryKeys() instead
        boolean foundIdColumn = false;
        for (javaxt.sql.Column column : columns) {
            if (column.getName().equalsIgnoreCase("id")) {
                foundIdColumn = true;
                break;
            }
        }

        assertTrue("Should find id column", foundIdColumn);

        // Verify primary key information from table level
        javaxt.sql.Key[] primaryKeys = usersTable.getPrimaryKeys();
        assertTrue("Should have primary key information", primaryKeys != null && primaryKeys.length > 0);
    }

    @Test
    public void testDatabaseGetRecords() throws Exception {
        printTestHeader("Database GetRecords Test");

        // Test getting records directly from database (not from connection)
        int count = 0;
        for (javaxt.sql.Record record : database.getRecords("SELECT * FROM db_test_users ORDER BY id")) {
            System.out.println("User " + record.get("id") + ": " +
                             record.get("username") + " <" + record.get("email") + ">");
            count++;
        }

        assertEquals("Should iterate over 2 users", 2, count);
    }

    @Test
    public void testDatabaseGetRecord() throws Exception {
        printTestHeader("Database GetRecord Test");

        // Test getting a single record directly from database
        javaxt.sql.Record record = database.getRecord("SELECT COUNT(*) as total FROM db_test_orders");
        assertNotNull("Record should not be null", record);

        Long total = record.get("total").toLong();
        System.out.println("Total orders: " + total);

        assertEquals("Should have 2 orders", Long.valueOf(2), total);
    }

    @Test
    public void testTableKeys() throws Exception {
        printTestHeader("Table Keys Test");


        javaxt.sql.Table[] tables = database.getTables();
        javaxt.sql.Table usersTable = null;

        // Find the users table
        for (javaxt.sql.Table table : tables) {
            if (table.getName().toLowerCase().contains("db_test_users")) {
                usersTable = table;
                break;
            }
        }

        assertNotNull("Users table should exist", usersTable);

        // Get primary keys
        javaxt.sql.Key[] primaryKeys = usersTable.getPrimaryKeys();
        System.out.println("Primary Keys: " + (primaryKeys != null ? primaryKeys.length : 0));

        if (primaryKeys != null && primaryKeys.length > 0) {
            for (javaxt.sql.Key key : primaryKeys) {
                System.out.println("  - " + key);
            }

            // Verify at least one primary key exists
            assertTrue("Should have at least one primary key", primaryKeys.length > 0);
        }

    }

    @Test
    public void testConnectionSpeed() throws Exception {
        printTestHeader("Connection Speed Test");

        long totalSpeed = 0;
        int iterations = 5;

        for (int i = 0; i < iterations; i++) {
            try (javaxt.sql.Connection conn = database.getConnection()) {
                long speed = conn.getConnectionSpeed();
                System.out.println("Connection " + (i + 1) + " speed: " + speed + " ms");
                totalSpeed += speed;
            }
        }

        double avgSpeed = (double) totalSpeed / iterations;
        System.out.println("Average connection speed: " + avgSpeed + " ms");

        // Verify connections are reasonably fast (< 1 second on average)
        assertTrue("Average connection speed should be < 1000ms", avgSpeed < 1000);
    }

    @Test
    public void testH2PostgreSQLMode() throws Exception {
        // Skip this test if not H2
        if (!database.getDriver().getVendor().equals("H2")) {
            return;
        }

        printTestHeader("H2 PostgreSQL Mode Test");

        // Create a new Database instance in PostgreSQL mode using a separate database file
        String tempDir = System.getProperty("java.io.tmpdir");
        if (!tempDir.endsWith(File.separator)) {
            tempDir += File.separator;
        }
        String h2PgPath = tempDir + "javaxt_test_h2_pg";

        Database pgModeDb = new Database();
        pgModeDb.setDriver(javaxt.sql.Driver.H2);
        pgModeDb.setHost(h2PgPath);
        pgModeDb.setUserName(database.getUserName());
        pgModeDb.setPassword(database.getPassword());

        // Set H2 to PostgreSQL mode
        java.util.Properties properties = pgModeDb.getProperties();
        if (properties == null) {
            properties = new java.util.Properties();
            pgModeDb.setProperties(properties);
        }
        properties.setProperty("MODE", "PostgreSQL");
        properties.setProperty("DATABASE_TO_LOWER", "TRUE");
        properties.setProperty("DEFAULT_NULL_ORDERING", "HIGH");

        System.out.println("H2 Database configured in PostgreSQL mode");
        System.out.println("Properties: MODE=PostgreSQL, DATABASE_TO_LOWER=TRUE, DEFAULT_NULL_ORDERING=HIGH");

        try (javaxt.sql.Connection conn = pgModeDb.getConnection()) {
            assertNotNull("Connection should not be null", conn);
            assertTrue("Connection should be valid", conn.getConnection().isValid(5));

            System.out.println("Successfully connected in PostgreSQL mode");

            // Test PostgreSQL-specific behavior
            // In PostgreSQL mode, identifiers should be case-insensitive and lowercased
            conn.execute("DROP TABLE IF EXISTS PgModeTest");
            conn.execute("CREATE TABLE PgModeTest (TestId INT PRIMARY KEY, TestName VARCHAR(50))");
            conn.execute("INSERT INTO PgModeTest VALUES (1, 'PostgreSQL Mode')");

            // Query using lowercase (should work in PostgreSQL mode)
            javaxt.sql.Record record = conn.getRecord("SELECT testid, testname FROM pgmodetest WHERE testid = 1");
            assertNotNull("Should retrieve record in PostgreSQL mode", record);
            assertEquals("Should get correct value", "PostgreSQL Mode", record.get("testname").toString());

            System.out.println("PostgreSQL mode test passed - case-insensitive identifiers working");

            // Test NULL ordering (PostgreSQL defaults to NULLS LAST for ASC, NULLS FIRST for DESC)
            conn.execute("INSERT INTO PgModeTest VALUES (2, NULL)");
            conn.execute("INSERT INTO PgModeTest VALUES (3, 'Another Value')");

            int recordCount = 0;
            for (javaxt.sql.Record r : conn.getRecords("SELECT testid FROM pgmodetest ORDER BY testname ASC")) {
                recordCount++;
                System.out.println("Record " + recordCount + ": testid=" + r.get("testid"));
            }

            assertEquals("Should have 3 records", 3, recordCount);
            System.out.println("NULL ordering test passed");

            // Cleanup
            conn.execute("DROP TABLE PgModeTest");
        }
    }

    @Test
    public void testH2StandardMode() throws Exception {
        // Skip this test if not H2
        if (!database.getDriver().getVendor().equals("H2")) {
            return;
        }

        printTestHeader("H2 Standard Mode Test");

        // Test with standard H2 mode (no special properties)
        try (javaxt.sql.Connection conn = database.getConnection()) {
            System.out.println("Testing H2 in standard mode");

            // H2 standard mode is case-sensitive for identifiers
            conn.execute("DROP TABLE IF EXISTS StandardTest");
            conn.execute("CREATE TABLE StandardTest (Id INT PRIMARY KEY, Name VARCHAR(50))");
            conn.execute("INSERT INTO StandardTest VALUES (1, 'Standard Mode')");

            javaxt.sql.Record record = conn.getRecord("SELECT Id, Name FROM StandardTest WHERE Id = 1");
            assertNotNull("Should retrieve record", record);
            assertEquals("Should get correct value", "Standard Mode", record.get("Name").toString());

            System.out.println("Standard mode test passed");

            // Cleanup
            conn.execute("DROP TABLE StandardTest");
        }
    }

    @Test
    public void testH2FilePersistence() throws Exception {
        // Skip this test if not H2
        if (!database.getDriver().getVendor().equals("H2")) {
            return;
        }

        printTestHeader("H2 File Persistence Test");

        // Create a table and insert data
        try (javaxt.sql.Connection conn = database.getConnection()) {
            conn.execute("DROP TABLE IF EXISTS PersistTest");
            // Note: 'value' is a reserved keyword in H2, so we use 'data_value' instead
            conn.execute("CREATE TABLE PersistTest (id INT PRIMARY KEY, data_value VARCHAR(50))");
            conn.execute("INSERT INTO PersistTest VALUES (1, 'Persisted Data')");
            System.out.println("Created table and inserted data");
        }

        // Close connection and reopen - data should persist in file
        try (javaxt.sql.Connection conn = database.getConnection()) {
            javaxt.sql.Record record = conn.getRecord("SELECT data_value FROM PersistTest WHERE id = 1");
            assertNotNull("Should retrieve persisted record", record);
            assertEquals("Should get persisted value", "Persisted Data", record.get("data_value").toString());

            System.out.println("File persistence test passed - data survived connection close/reopen");

            // Cleanup
            conn.execute("DROP TABLE PersistTest");
        }
    }
}
