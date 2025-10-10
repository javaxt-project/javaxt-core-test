package javaxt.core.test.sql;

import javaxt.sql.ConnectionPool;
import javaxt.sql.ConnectionPool.PoolStatistics;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import javax.sql.ConnectionPoolDataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

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
        // Skip all tests if no database is configured
        assumeTrue(
            "Skipping ConnectionPool health tests: " + ConnectionPoolTestConfig.getGlobalError(),
            ConnectionPoolTestConfig.hasValidConfiguration()
        );

        List<Object[]> params = new ArrayList<>();
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

        // Get a connection and release it
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        conn1.close();

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 1 recycled connection", 1, stats.recycledConnections);

        // Wait for idle timeout to expire
        Thread.sleep(1500);

        // Trigger a health check by getting another connection
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        conn2.close();

        // Wait a bit more and check again
        Thread.sleep(100);

        // The idle connection should have been removed by health monitoring
        // Note: Health monitoring runs every 30 seconds, so we can't reliably test this in a unit test
        // This test mainly verifies that the pool doesn't crash with short idle timeouts

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


        // Get a connection and release it
        java.sql.Connection conn1 = pool.getConnection().getConnection();
        conn1.close();

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("Should have 1 recycled connection", 1, stats.recycledConnections);

        // Wait for max age timeout to expire
        Thread.sleep(1500);

        // Trigger a health check by getting another connection
        java.sql.Connection conn2 = pool.getConnection().getConnection();
        conn2.close();

        // Wait a bit more and check again
        Thread.sleep(100);

        // The aged connection should have been removed by health monitoring
        // Note: Health monitoring runs every 30 seconds, so we can't reliably test this in a unit test
        // This test mainly verifies that the pool doesn't crash with short max age timeouts

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
}
