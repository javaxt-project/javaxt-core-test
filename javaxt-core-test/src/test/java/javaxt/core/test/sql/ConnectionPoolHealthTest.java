package javaxt.core.test.sql;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.ConnectionPoolDataSource;
import javaxt.sql.ConnectionPool;
import javaxt.sql.ConnectionPool.PoolStatistics;
import javaxt.sql.Database;
import javaxt.sql.Record;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for ConnectionPool health monitoring, validation, and lifecycle features.
 *
 * <p>Tests run against all configured databases (PostgreSQL, MySQL, etc.).
 * If no databases are configured, all tests will be skipped.</p>
 */
@RunWith(Parameterized.class)
public class ConnectionPoolHealthTest {

    private ConnectionPoolDataSource dataSource;
    private ConnectionPoolTestConfig.DatabaseConfig dbConfig;
    private static String lastPrintedDatabase = null;

    /**
     * Provides test parameters - one set for each configured database.
     */
    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        // Returning an empty list causes Parameterized to skip the class cleanly.
        // assumeTrue() thrown from @Parameters surfaces as a class-init error,
        // not a skip, so don't use it here.
        List<Object[]> params = new ArrayList<>();
        if (!ConnectionPoolTestConfig.hasValidConfiguration()) {
            return params;
        }
        for (ConnectionPoolTestConfig.DatabaseConfig config : ConnectionPoolTestConfig.getValidConfigurations()) {
            params.add(new Object[]{config.getType().getDisplayName(), config});
        }
        return params;
    }

    /**
     * Constructor that receives the database configuration for this test run.
     */
    public ConnectionPoolHealthTest(String displayName, ConnectionPoolTestConfig.DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    /**
     * Helper method to print test section headers with database identifier
     */
    private void printTestHeader(String testName) {
        System.out.println("\n=== " + dbConfig.getType().getDisplayName() + " - " + testName + " ===");
    }

    @Before
    public void setUp() throws Exception {
        // Setup DataSource using the configuration for this database
        this.dataSource = dbConfig.getConnectionPoolDataSource();

        // Initialize database
        try (Connection conn = DriverManager.getConnection(
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword())) {
            conn.createStatement().execute("DROP TABLE IF EXISTS health_test");
            conn.createStatement().execute("CREATE TABLE health_test (id INT PRIMARY KEY, name VARCHAR(50))");
            conn.createStatement().execute("INSERT INTO health_test VALUES (1, 'Test1'), (2, 'Test2')");
        }
    }

    @After
    public void tearDown() throws Exception {
        // Cleanup is handled in individual tests
    }

    // ==================== CONNECTION VALIDATION TESTS ====================

    @Test
    public void testConnectionValidationWithQuery() throws Exception {
        // Print database header once per database
        String dbName = dbConfig.getType().getDisplayName();
        if (!dbName.equals(lastPrintedDatabase)) {
            System.out.println("\n=== " + dbName + " - Health Monitoring Tests ===");
            lastPrintedDatabase = dbName;
        }

        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});






        // Get a connection and use it
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));

        // Perform a query
        try (PreparedStatement stmt = conn1.prepareStatement("SELECT COUNT(*) FROM health_test")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue("Should have results", rs.next());
                assertEquals("Should have 2 records", 2, rs.getInt(1));
            }
        }

        conn1.close();

        // Get the same connection again (should be recycled and validated)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));

        // Verify it still works
        try (PreparedStatement stmt = conn2.prepareStatement("SELECT COUNT(*) FROM health_test")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue("Should have results", rs.next());
                assertEquals("Should have 2 records", 2, rs.getInt(1));
            }
        }

        conn2.close();
        pool.close();
    }

    @Test
    public void testConnectionValidationWithoutQuery() throws Exception {
        // Create pool without validation query
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
        }});

        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));
        conn1.close();

        // Get the same connection again (should be recycled without validation)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));
        conn2.close();

        pool.close();
    }

    @Test
    public void testConnectionValidationWithEmptyQuery() throws Exception {
        // Create pool with empty validation query
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "");
        }});

        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));
        conn1.close();

        // Get the same connection again (should be recycled without validation)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));
        conn2.close();

        pool.close();
    }

    @Test
    public void testConnectionValidationWithCustomQuery() throws Exception {
        // Create pool with custom validation query
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT COUNT(*) FROM health_test");
        }});

        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));
        conn1.close();

        // Get the same connection again (should be recycled and validated with custom query)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));
        conn2.close();

        pool.close();
    }

    // ==================== IDLE TIMEOUT TESTS ====================

    @Test
    public void testIdleTimeout() throws Exception {
        // Create pool with very short idle timeout (1 second)
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 1);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Fill all 3 slots so we have an observable before/after.
        List<java.sql.Connection> connections = new ArrayList<>();
        for (int i = 0; i < 3; i++) connections.add(pool.getConnection().getConnection());
        for (java.sql.Connection c : connections) c.close();

        PoolStatistics before = pool.getPoolStatistics();
        assertEquals("Should have 3 recycled connections before timeout", 3, before.recycledConnections);
        assertEquals("Should have 3 total connections before timeout", 3, before.totalConnections);

        // Wait past the idle timeout.
        Thread.sleep(1500);

        // Drive the health-check pass synchronously (otherwise it would only
        // run every 30 seconds; this is why we have forceHealthCheck()).
        pool.forceHealthCheck();

        PoolStatistics after = pool.getPoolStatistics();
        // Eviction must observably reduce either recycled or total. Warm-up
        // can reseed up to minConnections (1 for pool size 3), so we expect
        // a strict decrease in at least one of recycled or total.
        assertTrue("Idle eviction should reduce recycled or total; "
                       + "before(r=" + before.recycledConnections + ",t=" + before.totalConnections + ") "
                       + "after(r=" + after.recycledConnections + ",t=" + after.totalConnections + ")",
                   after.totalConnections < before.totalConnections
                || after.recycledConnections < before.recycledConnections);

        pool.close();
    }

    @Test
    public void testMaxAgeTimeout() throws Exception {
        // Create pool with very short max age (1 second)
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 1);
            put("validationQuery", "SELECT 1");
        }});

        // Fill all 3 slots so we have an observable before/after.
        List<java.sql.Connection> connections = new ArrayList<>();
        for (int i = 0; i < 3; i++) connections.add(pool.getConnection().getConnection());
        for (java.sql.Connection c : connections) c.close();

        PoolStatistics before = pool.getPoolStatistics();
        assertEquals("Should have 3 recycled connections before timeout", 3, before.recycledConnections);
        assertEquals("Should have 3 total connections before timeout", 3, before.totalConnections);

        // Wait past maxAge.
        Thread.sleep(1500);

        // Drive the health-check pass synchronously.
        pool.forceHealthCheck();

        PoolStatistics after = pool.getPoolStatistics();
        // Aged connections must observably shrink the pool. Warm-up can reseed
        // up to minConnections (1 for pool size 3), so we expect a strict
        // decrease in at least one of recycled or total.
        assertTrue("Max-age eviction should reduce recycled or total; "
                       + "before(r=" + before.recycledConnections + ",t=" + before.totalConnections + ") "
                       + "after(r=" + after.recycledConnections + ",t=" + after.totalConnections + ")",
                   after.totalConnections < before.totalConnections
                || after.recycledConnections < before.recycledConnections);

        pool.close();
    }

    // ==================== MIN CONNECTIONS TESTS ====================

    @Test
    public void testMinConnectionsWarmUp() throws Exception {
        // Create pool with min connections feature
        ConnectionPool pool = new ConnectionPool(dataSource, 10, 5);

        // Check initial state
        PoolStatistics stats = pool.getPoolStatistics();
        assertTrue("Should have min connections >= 1", stats.minConnections >= 1);
        assertEquals("Should have 0 active connections initially", 0, stats.activeConnections);
        assertEquals("Should have 0 recycled connections initially", 0, stats.recycledConnections);

        // Get a connection to trigger warm-up
        java.sql.Connection conn = pool.getConnection().getConnection();
        conn.close();

        // Wait a bit for health monitoring to potentially warm up the pool
        Thread.sleep(100);

        stats = pool.getPoolStatistics();
        assertTrue("Should have at least 1 recycled connection", stats.recycledConnections >= 1);

        pool.close();
    }

    @Test
    public void testMinConnectionsCalculation() throws Exception {
        // Test different pool sizes to verify min connections calculation

        // Small pool (5 connections) - should have min 1
        ConnectionPool pool1 = new ConnectionPool(dataSource, 5, 5);
        PoolStatistics stats1 = pool1.getPoolStatistics();
        assertEquals("Min connections for pool size 5", 1, stats1.minConnections);
        pool1.close();

        // Medium pool (10 connections) - should have min 2 (20% of 10)
        ConnectionPool pool2 = new ConnectionPool(dataSource, 10, 5);
        PoolStatistics stats2 = pool2.getPoolStatistics();
        assertEquals("Min connections for pool size 10", 2, stats2.minConnections);
        pool2.close();

        // Large pool (50 connections) - should have min 10 (20% of 50)
        ConnectionPool pool3 = new ConnectionPool(dataSource, 50, 5);
        PoolStatistics stats3 = pool3.getPoolStatistics();
        assertEquals("Min connections for pool size 50", 10, stats3.minConnections);
        pool3.close();
    }

    @Test
    public void testWrapperReuseAndActiveCountIntegrity() throws Exception {
        printTestHeader("Wrapper Reuse and Active Count Integrity Test");

        // This test specifically validates the wrapper pooling optimization
        // It ensures that:
        // 1. Wrappers are properly reused without creating new objects
        // 2. Active connection counter remains consistent during warm-up and recycling
        // 3. No negative counter errors occur

        ConnectionPool pool = new ConnectionPool(dataSource, 10, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Initial state
        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 0 active connections initially", 0, stats.activeConnections);
        assertEquals("Should have 0 recycled connections initially", 0, stats.recycledConnections);

        // Get and release multiple connections rapidly to test wrapper reuse
        for (int i = 0; i < 10; i++) {
            javaxt.sql.Connection conn = pool.getConnection();

            // Verify connection works
            java.sql.Connection rawConn = conn.getConnection();
            assertTrue("Connection should be valid", rawConn.isValid(5));

            // Verify active count increased
            stats = pool.getPoolStatistics();
            assertTrue("Should have at least 1 active connection", stats.activeConnections >= 1);

            // Close and verify recycling
            conn.close();

            // After close, active should decrease and recycled should increase
            stats = pool.getPoolStatistics();
            assertTrue("Active connections should not be negative", stats.activeConnections >= 0);
        }

        // Final state check
        stats = pool.getPoolStatistics();
        assertEquals("Should have 0 active connections at end", 0, stats.activeConnections);
        assertTrue("Should have recycled connections", stats.recycledConnections > 0);
        assertTrue("Active count should not go negative", stats.activeConnections >= 0);

        // Test concurrent acquisition and release to stress test wrapper reuse
        int numThreads = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < 10; j++) {
                        javaxt.sql.Connection conn = pool.getConnection();
                        java.sql.Connection rawConn = conn.getConnection();

                        // Execute a simple query
                        try (PreparedStatement stmt = rawConn.prepareStatement("SELECT 1")) {
                            stmt.executeQuery();
                        }

                        conn.close();
                    }

                } catch (AssertionError e) {
                    // Catch the specific error we're testing for
                    if (e.getMessage().contains("Active connections count went negative")) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } else {
                        throw e;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        assertTrue("All threads should complete within 30 seconds",
                   finishLatch.await(30, TimeUnit.SECONDS));

        // Verify no errors occurred
        assertEquals("Should have no active count errors", 0, errorCount.get());

        // Final verification
        stats = pool.getPoolStatistics();
        assertEquals("Should have 0 active connections after all threads complete", 0, stats.activeConnections);
        assertTrue("Should have recycled connections", stats.recycledConnections > 0);
        assertTrue("Active count should not be negative", stats.activeConnections >= 0);

        pool.close();
    }

    // ==================== HEALTH MONITORING TESTS ====================

    @Test
    public void testHealthMonitoringLifecycle() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 5, 5);

        // Verify health monitoring is active
        assertFalse("Pool should not be closed", pool.isClosed());

        // Get some connections to create activity
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        conn1.close();
        conn2.close();

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 2 recycled connections", 2, stats.recycledConnections);

        // Close the pool
        pool.close();

        assertTrue("Pool should be closed", pool.isClosed());

        // Health monitoring should be stopped
        // We can't directly test this, but the pool should be in a clean state
    }

    @Test
    public void testHealthMonitoringWithValidation() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Create some connections
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        conn1.close();
        conn2.close();

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 2 recycled connections", 2, stats.recycledConnections);

        // Wait a bit and get another connection to trigger validation
        Thread.sleep(100);
        java.sql.Connection conn3 = pool.getConnection().getConnection();

        // Verify the connection works
        try (PreparedStatement stmt = conn3.prepareStatement("SELECT COUNT(*) FROM health_test")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue("Should have results", rs.next());
                assertEquals("Should have 2 records", 2, rs.getInt(1));
            }
        }

        conn3.close();
        pool.close();
    }

    // ==================== CONNECTION LIFECYCLE TESTS ====================

    @Test
    public void testConnectionLifecycleTracking() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 5, 5);

        // Track connection lifecycle
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        PoolStatistics stats1 = pool.getPoolStatistics();
        assertEquals("Should have 1 active connection", 1, stats1.activeConnections);
        assertEquals("Should have 0 recycled connections", 0, stats1.recycledConnections);

        conn1.close();
        PoolStatistics stats2 = pool.getPoolStatistics();
        assertEquals("Should have 0 active connections", 0, stats2.activeConnections);
        assertEquals("Should have 1 recycled connection", 1, stats2.recycledConnections);

        // Get another connection (should reuse the recycled one)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        PoolStatistics stats3 = pool.getPoolStatistics();
        assertEquals("Should have 1 active connection", 1, stats3.activeConnections);
        assertEquals("Should have 0 recycled connections", 0, stats3.recycledConnections);

        conn2.close();
        pool.close();
    }

    @Test
    public void testConnectionWrapperTracking() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 3, 5000);

        // Get multiple connections
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        java.sql.Connection conn2 = pool.getConnection().getConnection();

        // Release them
        conn1.close();
        conn2.close();

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 2 recycled connections", 2, stats.recycledConnections);

        // Get connections again (should reuse the recycled ones)
        java.sql.Connection conn3 = pool.getConnection().getConnection();
        java.sql.Connection conn4 = pool.getConnection().getConnection();

        stats = pool.getPoolStatistics();
        assertEquals("Should have 0 recycled connections", 0, stats.recycledConnections);
        assertEquals("Should have 2 active connections", 2, stats.activeConnections);

        conn3.close();
        conn4.close();
        pool.close();
    }

    // ==================== VALIDATION TIMEOUT TESTS ====================

    @Test
    public void testValidationTimeout() throws Exception {
        // Create pool with validation timeout
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Set a very short validation timeout (this would need to be configurable)
        // For now, we'll just test that validation works with the default timeout

        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));
        conn1.close();

        // Get the same connection again (should be validated)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));
        conn2.close();

        pool.close();
    }

    // ==================== EDGE CASES ====================

    @Test
    public void testValidationWithClosedConnection() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Get a connection
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        assertTrue("Connection should be valid", conn1.isValid(5));
        conn1.close();

        // Wait a bit
        Thread.sleep(100);

        // Get another connection (should reuse the recycled one)
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        assertTrue("Recycled connection should be valid", conn2.isValid(5));

        // Verify it works
        try (PreparedStatement stmt = conn2.prepareStatement("SELECT COUNT(*) FROM health_test")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue("Should have results", rs.next());
                assertEquals("Should have 2 records", 2, rs.getInt(1));
            }
        }

        conn2.close();
        pool.close();
    }

    @Test
    public void testConcurrentValidation() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 5, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);

        // Create threads that get and release connections concurrently
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();

                    java.sql.Connection conn = pool.getConnection().getConnection();
                    assertTrue("Connection should be valid", conn.isValid(5));

                    // Perform a simple query
                    try (PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                        stmt.executeQuery();
                    }

                    conn.close();

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        assertTrue("All threads should complete within 10 seconds",
                   finishLatch.await(10, TimeUnit.SECONDS));

        pool.close();
    }

    @Test
    public void testHealthMonitoringWithPoolClose() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("idleTimeout", 30);
            put("maxAge", 60);
            put("validationQuery", "SELECT 1");
        }});

        // Get some connections
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        java.sql.Connection conn2 = pool.getConnection().getConnection();

        // Close the pool while connections are active
        pool.close();

        assertTrue("Pool should be closed", pool.isClosed());

        // Connections should still be valid
        assertTrue("Connection should still be valid", conn1.isValid(5));
        assertTrue("Connection should still be valid", conn2.isValid(5));

        // Close the connections
        conn1.close();
        conn2.close();
    }

    @Test
    public void testRealWorldThreadPoolPattern() throws Exception {
        printTestHeader("Real-World ThreadPool Pattern Test");

        // Create a database with connection pool (like FileIndex does)
        Database db = new Database();
        db.setDriver(getDriverFromDataSource());
        db.setHost(getHostFromDataSource());
        db.setName(getDatabaseNameFromDataSource());
        db.setUserName(getUserFromDataSource());
        db.setPassword(getPasswordFromDataSource());

        // Initialize connection pool
        db.setConnectionPoolSize(10);
        db.initConnectionPool();
        ConnectionPool pool = db.getConnectionPool();

        try {
            System.out.println("Database connection pool initialized with size: 10");

            // Create test table
            try (javaxt.sql.Connection conn = db.getConnection()) {
                conn.execute("DROP TABLE IF EXISTS threadpool_test");
                conn.execute("CREATE TABLE threadpool_test (id INT PRIMARY KEY, thread_name VARCHAR(100), timestamp BIGINT)");
            }

            // Track results
            final AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger errorCount = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(1);

            // Create a custom ThreadPool similar to FileIndex usage
            int numThreads = 10;
            int poolSize = 100;

            javaxt.utils.ThreadPool threadPool = new javaxt.utils.ThreadPool(numThreads, poolSize) {
                public void process(Object obj) {
                    Integer taskId = (Integer) obj;

                    try {
                        // Get connection from pool (like FileIndex does)
                        try (javaxt.sql.Connection conn = db.getConnection()) {
                            // Simulate database work
                            String threadName = Thread.currentThread().getName();
                            long timestamp = System.currentTimeMillis();

                            conn.execute("INSERT INTO threadpool_test VALUES (" +
                                       taskId + ", '" + threadName + "', " + timestamp + ")");

                            // Simulate some work
                            Thread.sleep(10);

                            // Verify the insert worked
                            Record record = conn.getRecord("SELECT COUNT(*) FROM threadpool_test WHERE id = " + taskId);
                            if (record != null && record.get(0).toInteger() == 1) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        }
                        // Connection.close() called automatically here

                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        System.err.println("Error in thread " + Thread.currentThread().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                public void exit() {
                    latch.countDown();
                }
            }.start();

            // Add tasks to the thread pool
            System.out.println("Adding 100 tasks to ThreadPool with " + numThreads + " worker threads");
            for (int i = 0; i < 100; i++) {
                threadPool.add(i);
            }

            // Signal completion and wait for all tasks to finish
            threadPool.done();
            threadPool.join();

            // Wait for exit to be called
            assertTrue("ThreadPool should complete within 30 seconds",
                      latch.await(30, TimeUnit.SECONDS));

            System.out.println("All tasks completed:");
            System.out.println("  Success: " + successCount.get());
            System.out.println("  Errors: " + errorCount.get());

            // Verify results
            assertEquals("Should have no errors", 0, errorCount.get());
            assertEquals("Should have 100 successful operations", 100, successCount.get());

            // Check pool statistics
            PoolStatistics stats = pool.getPoolStatistics();

            System.out.println("\nFinal pool statistics:");
            System.out.println("  Active connections: " + stats.activeConnections);
            System.out.println("  Recycled connections: " + stats.recycledConnections);
            System.out.println("  Total connections: " + stats.totalConnections);

            // All connections should be returned to pool
            assertEquals("All connections should be returned to pool", 0, stats.activeConnections);
            assertTrue("Should have recycled connections", stats.recycledConnections > 0);

            // Verify data integrity
            try (javaxt.sql.Connection conn = db.getConnection()) {
                Record countRecord = conn.getRecord("SELECT COUNT(*) FROM threadpool_test");
                int count = countRecord.get(0).toInteger();
                assertEquals("Should have 100 records in database", 100, count);

                // Cleanup
                conn.execute("DROP TABLE IF EXISTS threadpool_test");
            }

            System.out.println("Real-world ThreadPool pattern test passed!");
        } finally {
            pool.close();
        }
    }

    // Helpers that build the javaxt.sql.Database configuration for the current
    // parameterized DatabaseType. Dispatch on dbConfig.getType() rather than on
    // the dataSource class so we can distinguish H2 in-memory from H2 file mode
    // (both use org.h2.jdbcx.JdbcDataSource).

    private javaxt.sql.Driver getDriverFromDataSource() {
        switch (dbConfig.getType()) {
            case POSTGRESQL: return javaxt.sql.Driver.PostgreSQL;
            case MYSQL:      return javaxt.sql.Driver.MySQL;
            case H2:
            case H2_FILE:    return javaxt.sql.Driver.H2;
            default:
                throw new IllegalStateException("Unsupported database type: " + dbConfig.getType());
        }
    }

    private String getHostFromDataSource() {
        switch (dbConfig.getType()) {
            case POSTGRESQL:
            case MYSQL:
                return dbConfig.getHost();
            case H2:
                // Database treats host="memory" (or empty) as jdbc:h2:mem:
                return "memory";
            case H2_FILE: {
                // For file mode we need a path. The db.name property already
                // carries a "file:<path>;<params>" prefix; strip the "file:"
                // and keep the path + params (notably DB_CLOSE_DELAY=-1, which
                // must be on every URL or H2 will tear down the DB the moment
                // the last connection closes between checkouts). Database
                // prepends "jdbc:h2:file:" and appends "/" + name, which means
                // we set name="" to avoid an extra "/<name>" suffix.
                String n = dbConfig.getDatabaseName();
                return n.startsWith("file:") ? n.substring(5) : n;
            }
            default:
                return "localhost";
        }
    }

    private String getDatabaseNameFromDataSource() {
        switch (dbConfig.getType()) {
            case POSTGRESQL:
            case MYSQL:
                return dbConfig.getDatabaseName();
            case H2:
                return "test";
            case H2_FILE:
                // The file path is carried in setHost(); leave name empty so
                // Database doesn't add a spurious "/<name>" segment to the URL.
                return "";
            default:
                return "test";
        }
    }

    private String getUserFromDataSource() {
        return dbConfig.getUser();
    }

    private String getPasswordFromDataSource() {
        return dbConfig.getPassword();
    }
}
