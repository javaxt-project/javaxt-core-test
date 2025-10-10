package javaxt.core.test.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.ConnectionPoolDataSource;
import javaxt.sql.ConnectionPool;
import javaxt.sql.ConnectionPool.PoolStatistics;
import org.junit.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Performance-focused tests for ConnectionPool class.
 * These tests measure throughput, latency, and resource utilization.
 *
 * <p>Tests run against all configured databases (PostgreSQL, MySQL, etc.).
 * If no databases are configured, all tests will be skipped.</p>
 */
@RunWith(Parameterized.class)
public class ConnectionPoolPerformanceTest {

    private ConnectionPoolDataSource dataSource;
    private ConnectionPoolTestConfig.DatabaseConfig dbConfig;

    /**
     * Provides test parameters - one set for each configured database.
     */
    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        // Skip all tests if no database is configured
        assumeTrue(
            "Skipping ConnectionPool performance tests: " + ConnectionPoolTestConfig.getGlobalError(),
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
    public ConnectionPoolPerformanceTest(String displayName, ConnectionPoolTestConfig.DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    /**
     * Helper method to print test section headers with database identifier
     */
    private void printTestHeader(String testName) {
        System.out.println("\n=== " + dbConfig.getType().getDisplayName() + " - " + testName + " ===");
    }

    /**
     * Checks if HikariCP benchmarking is enabled.
     * Enable with: mvn test -Dbenchmark.enabled=true
     */
    private boolean shouldBenchmarkAgainstHikari() {
        return Boolean.parseBoolean(System.getProperty("benchmark.enabled", "false"));
    }

    /**
     * Creates a HikariCP DataSource with equivalent configuration to javaxt ConnectionPool.
     */
    private HikariDataSource createHikariPool(int poolSize, int timeoutSeconds) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUser());
        config.setPassword(dbConfig.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.max(1, poolSize / 5));
        config.setConnectionTimeout(timeoutSeconds * 1000L);
        return new HikariDataSource(config);
    }

    @Before
    public void setUp() throws Exception {
        // Setup DataSource using the configuration for this database
        this.dataSource = dbConfig.getConnectionPoolDataSource();

        // Initialize database with test data
        try (Connection conn = DriverManager.getConnection(
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword())) {
            conn.createStatement().execute("DROP TABLE IF EXISTS perf_test");
            conn.createStatement().execute("CREATE TABLE perf_test (id INT PRIMARY KEY, data VARCHAR(100), created_at TIMESTAMP)");

            // Insert test data
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO perf_test VALUES (?, ?, ?)")) {
                for (int i = 1; i <= 1000; i++) {
                    stmt.setInt(1, i);
                    stmt.setString(2, "Test data " + i);
                    stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }
    }

    // ==================== THROUGHPUT TESTS ====================

    @Test
    public void testSequentialThroughput() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 10, 10000);

        int numOperations = 1000;
        long startTime = System.nanoTime();

        for (int i = 0; i < numOperations; i++) {
            java.sql.Connection conn = pool.getConnection().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM perf_test WHERE id = ?")) {
                stmt.setInt(1, (i % 1000) + 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                }
            }
            conn.close();
        }

        long endTime = System.nanoTime();

        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double throughput = numOperations / (totalTimeMs / 1000.0);

        printTestHeader("Sequential Throughput Test");
        System.out.println("Operations: " + numOperations);
        System.out.println("Total time: " + String.format("%.3f", totalTimeMs) + " ms");
        System.out.println("Throughput: " + String.format("%.0f", throughput) + " ops/sec");
        System.out.println("Avg time per operation: " + String.format("%.3f", totalTimeMs / numOperations) + " ms");

        // Optional HikariCP comparison
        if (shouldBenchmarkAgainstHikari()) {
            HikariDataSource hikariPool = createHikariPool(10, 10);

            long hikariStart = System.nanoTime();
            for (int i = 0; i < numOperations; i++) {
                try (Connection conn = hikariPool.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM perf_test WHERE id = ?")) {
                    stmt.setInt(1, (i % 1000) + 1);
                    try (ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                    }
                }
            }
            long hikariEnd = System.nanoTime();

            double hikariTimeMs = (hikariEnd - hikariStart) / 1_000_000.0;
            double hikariThroughput = numOperations / (hikariTimeMs / 1000.0);

            System.out.println("\n--- HikariCP Comparison ---");
            System.out.printf("HikariCP Throughput: %.0f ops/sec  (javaxt: %.0f ops/sec) - ", hikariThroughput, throughput);
            if (throughput > hikariThroughput) {
                System.out.printf("javaxt is %.1f%% FASTER\n", ((throughput - hikariThroughput) / hikariThroughput * 100));
            } else {
                System.out.printf("HikariCP is %.1f%% faster\n", ((hikariThroughput - throughput) / throughput * 100));
            }

            hikariPool.close();
        }

        pool.close();

        // Should achieve reasonable throughput
        assertTrue("Should achieve at least 100 ops/sec", throughput > 100);
    }

    @Test
    public void testConcurrentThroughput() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 20, 10);

        int numThreads = 10;
        int operationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        AtomicInteger totalOperations = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.nanoTime();

        // Create worker threads
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < operationsPerThread; j++) {
                        java.sql.Connection conn = pool.getConnection().getConnection();
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                            stmt.setInt(1, (threadId * operationsPerThread + j) % 1000 + 1);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    rs.getString(1);
                                }
                            }
                        }
                        conn.close();
                        totalOperations.incrementAndGet();
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

        long endTime = System.nanoTime();

        int totalOps = totalOperations.get();
        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalOps / (totalTimeMs / 1000.0);

        printTestHeader("Concurrent Throughput Test");
        System.out.println("Threads: " + numThreads);
        System.out.println("Operations per thread: " + operationsPerThread);
        System.out.println("Total operations: " + totalOps);
        System.out.println("Total time: " + String.format("%.3f", totalTimeMs) + " ms");
        System.out.println("Throughput: " + String.format("%.0f", throughput) + " ops/sec");
        System.out.println("Avg time per operation: " + String.format("%.3f", totalTimeMs / totalOps) + " ms");

        // Optional HikariCP comparison
        if (shouldBenchmarkAgainstHikari()) {
            HikariDataSource hikariPool = createHikariPool(20, 10);

            CountDownLatch hikariStart = new CountDownLatch(1);
            CountDownLatch hikariFinish = new CountDownLatch(numThreads);
            AtomicInteger hikariOps = new AtomicInteger(0);

            long hikariStartTime = System.nanoTime();

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                new Thread(() -> {
                    try {
                        hikariStart.await();
                        for (int j = 0; j < operationsPerThread; j++) {
                            try (Connection conn = hikariPool.getConnection();
                                 PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                                stmt.setInt(1, (threadId * operationsPerThread + j) % 1000 + 1);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    if (rs.next()) rs.getString(1);
                                }
                            }
                            hikariOps.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        hikariFinish.countDown();
                    }
                }).start();
            }

            hikariStart.countDown();
            hikariFinish.await(30, TimeUnit.SECONDS);
            long hikariEndTime = System.nanoTime();

            double hikariTimeMs = (hikariEndTime - hikariStartTime) / 1_000_000.0;
            double hikariThroughput = hikariOps.get() / (hikariTimeMs / 1000.0);

            System.out.println("\n--- HikariCP Comparison ---");
            System.out.printf("HikariCP Throughput: %.0f ops/sec  (javaxt: %.0f ops/sec) - ", hikariThroughput, throughput);
            if (throughput > hikariThroughput) {
                System.out.printf("javaxt is %.1f%% FASTER\n", ((throughput - hikariThroughput) / hikariThroughput * 100));
            } else {
                System.out.printf("HikariCP is %.1f%% faster\n", ((hikariThroughput - throughput) / throughput * 100));
            }

            hikariPool.close();
        }

        pool.close();

        // Check for errors first
        if (errorCount.get() > 0) {
            System.err.println("Errors occurred in " + errorCount.get() + " threads:");
            for (Exception e : exceptions) {
                e.printStackTrace();
            }
            fail("Test failed due to exceptions in background threads: " + errorCount.get() + " errors");
        }

        // Should complete all operations
        assertEquals("Should complete all operations", numThreads * operationsPerThread, totalOps);

        // Should achieve reasonable concurrent throughput
        assertTrue("Should achieve at least 200 ops/sec concurrent", throughput > 200);
    }

    // ==================== LATENCY TESTS ====================

    @Test
    public void testConnectionAcquisitionLatency() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 10, 10);

        int numSamples = 1000;
        List<Long> latencies = new ArrayList<>();

        // Warm up the pool
        for (int i = 0; i < 10; i++) {
            java.sql.Connection conn = pool.getConnection().getConnection();
            conn.close();
        }

        // Measure acquisition latencies
        for (int i = 0; i < numSamples; i++) {
            long startTime = System.nanoTime();
            java.sql.Connection conn = pool.getConnection().getConnection();
            long endTime = System.nanoTime();

            latencies.add(endTime - startTime);
            conn.close();
        }

        // Calculate statistics
        Collections.sort(latencies);
        double avgLatencyNs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double avgLatencyMs = avgLatencyNs / 1_000_000.0;

        long p50Ns = latencies.get((int) (numSamples * 0.5));
        long p95Ns = latencies.get((int) (numSamples * 0.95));
        long p99Ns = latencies.get((int) (numSamples * 0.99));

        printTestHeader("Connection Acquisition Latency Test");
        System.out.println("Samples: " + numSamples);
        System.out.println("Average latency: " + String.format("%.3f", avgLatencyMs) + " ms");
        System.out.println("P50 latency: " + String.format("%.3f", p50Ns / 1_000_000.0) + " ms");
        System.out.println("P95 latency: " + String.format("%.3f", p95Ns / 1_000_000.0) + " ms");
        System.out.println("P99 latency: " + String.format("%.3f", p99Ns / 1_000_000.0) + " ms");

        // Optional HikariCP comparison
        if (shouldBenchmarkAgainstHikari()) {
            HikariDataSource hikariPool = createHikariPool(10, 10);

            // Warm up HikariCP
            for (int i = 0; i < 10; i++) {
                Connection conn = hikariPool.getConnection();
                conn.close();
            }

            // Measure HikariCP latencies
            List<Long> hikariLatencies = new ArrayList<>();
            for (int i = 0; i < numSamples; i++) {
                long startTime = System.nanoTime();
                Connection conn = hikariPool.getConnection();
                long endTime = System.nanoTime();
                hikariLatencies.add(endTime - startTime);
                conn.close();
            }

            Collections.sort(hikariLatencies);
            double hikariAvg = hikariLatencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            long hikariP50 = hikariLatencies.get(numSamples / 2);
            long hikariP95 = hikariLatencies.get((int) (numSamples * 0.95));
            long hikariP99 = hikariLatencies.get((int) (numSamples * 0.99));

            System.out.println("\n--- HikariCP Comparison ---");
            System.out.printf("HikariCP Average:   %.3f ms  (javaxt: %.3f ms) - ", hikariAvg, avgLatencyMs);
            if (avgLatencyMs < hikariAvg) {
                System.out.printf("javaxt is %.1f%% FASTER\n", ((hikariAvg - avgLatencyMs) / hikariAvg * 100));
            } else {
                System.out.printf("HikariCP is %.1f%% faster\n", ((avgLatencyMs - hikariAvg) / avgLatencyMs * 100));
            }
            System.out.printf("HikariCP P50:       %.3f ms  (javaxt: %.3f ms)\n", hikariP50 / 1_000_000.0, p50Ns / 1_000_000.0);
            System.out.printf("HikariCP P95:       %.3f ms  (javaxt: %.3f ms)\n", hikariP95 / 1_000_000.0, p95Ns / 1_000_000.0);
            System.out.printf("HikariCP P99:       %.3f ms  (javaxt: %.3f ms)\n", hikariP99 / 1_000_000.0, p99Ns / 1_000_000.0);

            hikariPool.close();
        }

        pool.close();

        // Latency should be reasonable
        assertTrue("Average latency should be less than 5ms", avgLatencyMs < 5.0);
        assertTrue("P95 latency should be less than 10ms", (p95Ns / 1_000_000.0) < 10.0);
    }

    @Test
    public void testEndToEndLatency() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 10, 10);

        int numSamples = 500;
        List<Long> latencies = new ArrayList<>();

        // Warm up
        for (int i = 0; i < 10; i++) {
            java.sql.Connection conn = pool.getConnection().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                stmt.executeQuery();
            }
            conn.close();
        }

        // Measure end-to-end latencies (acquisition + query + release)
        for (int i = 0; i < numSamples; i++) {
            long startTime = System.nanoTime();

            java.sql.Connection conn = pool.getConnection().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                stmt.setInt(1, (i % 1000) + 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                }
            }
            conn.close();

            long endTime = System.nanoTime();
            latencies.add(endTime - startTime);
        }

        // Calculate statistics
        Collections.sort(latencies);
        double avgLatencyNs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double avgLatencyMs = avgLatencyNs / 1_000_000.0;

        long p50Ns = latencies.get((int) (numSamples * 0.5));
        long p95Ns = latencies.get((int) (numSamples * 0.95));
        long p99Ns = latencies.get((int) (numSamples * 0.99));

        printTestHeader("End-to-End Latency Test");
        System.out.println("Samples: " + numSamples);
        System.out.println("Average latency: " + String.format("%.3f", avgLatencyMs) + " ms");
        System.out.println("P50 latency: " + String.format("%.3f", p50Ns / 1_000_000.0) + " ms");
        System.out.println("P95 latency: " + String.format("%.3f", p95Ns / 1_000_000.0) + " ms");
        System.out.println("P99 latency: " + String.format("%.3f", p99Ns / 1_000_000.0) + " ms");

        pool.close();

        // End-to-end latency should be reasonable
        assertTrue("Average end-to-end latency should be less than 10ms", avgLatencyMs < 10.0);
        assertTrue("P95 end-to-end latency should be less than 20ms", (p95Ns / 1_000_000.0) < 20.0);
    }

    // ==================== SCALABILITY TESTS ====================

    @Test
    public void testPoolSizeScalability() throws Exception {
        int[] poolSizes = {1, 5, 10, 20, 50};
        int operationsPerTest = 200;

        printTestHeader("Pool Size Scalability Test");
        System.out.println("Operations per test: " + operationsPerTest);

        for (int poolSize : poolSizes) {
            ConnectionPool pool = new ConnectionPool(dataSource, poolSize, 10);

            long startTime = System.nanoTime();
            for (int i = 0; i < operationsPerTest; i++) {
                java.sql.Connection conn = pool.getConnection().getConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                    stmt.executeQuery();
                }
                conn.close();
            }
            long endTime = System.nanoTime();

            double totalTimeMs = (endTime - startTime) / 1_000_000.0;
            double throughput = operationsPerTest / (totalTimeMs / 1000.0);

            System.out.println("Pool size " + poolSize + ": " + String.format("%.0f", throughput) + " ops/sec");

            pool.close();
        }
    }

    @Test
    public void testThreadScalability() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 20, 10);

        int[] threadCounts = {1, 2, 5, 10, 20, 50};
        int operationsPerThread = 50;

        printTestHeader("Thread Scalability Test");
        System.out.println("Operations per thread: " + operationsPerThread);

        for (int numThreads : threadCounts) {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(numThreads);
            AtomicInteger totalOperations = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            long startTime = System.nanoTime();

            // Create worker threads
            for (int i = 0; i < numThreads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();

                        for (int j = 0; j < operationsPerThread; j++) {
                            java.sql.Connection conn = pool.getConnection().getConnection();
                            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM perf_test")) {
                                stmt.executeQuery();
                            }
                            conn.close();
                            totalOperations.incrementAndGet();
                        }

                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        exceptions.add(e);
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                }).start();
            }

            // Start all threads
            startLatch.countDown();

            // Wait for completion (with timeout)
            try {
                finishLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long endTime = System.nanoTime();

            int totalOps = totalOperations.get();
            double totalTimeMs = (endTime - startTime) / 1_000_000.0;
            double throughput = totalOps / (totalTimeMs / 1000.0);

            System.out.println("Threads " + numThreads + ": " + String.format("%.0f", throughput) + " ops/sec");

            // Check for errors
            if (errorCount.get() > 0) {
                System.err.println("Errors occurred in " + errorCount.get() + " threads for thread count " + numThreads + ":");
                for (Exception e : exceptions) {
                    e.printStackTrace();
                }
                fail("Test failed due to exceptions in background threads: " + errorCount.get() + " errors");
            }
        }

        pool.close();
    }

    // ==================== MEMORY AND RESOURCE TESTS ====================

    @Test
    public void testMemoryUsage() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 50, 10);

        // Force garbage collection to get baseline
        System.gc();
        Thread.sleep(100);

        long baselineMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Create and use many connections
        List<java.sql.Connection> connections = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            java.sql.Connection conn = pool.getConnection().getConnection();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                stmt.setInt(1, (i % 1000) + 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                }
            }
            connections.add(conn);
        }

        // Measure memory usage with connections held
        System.gc();
        Thread.sleep(100);
        long peakMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Release all connections
        for (java.sql.Connection conn : connections) {
            conn.close();
        }

        // Measure memory usage after release
        System.gc();
        Thread.sleep(100);
        long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        long memoryIncrease = peakMemory - baselineMemory;
        long memoryAfterRelease = finalMemory - baselineMemory;

        printTestHeader("Memory Usage Test");
        System.out.println("Baseline memory: " + (baselineMemory / 1024 / 1024) + " MB");
        System.out.println("Peak memory (50 connections): " + (peakMemory / 1024 / 1024) + " MB");
        System.out.println("Final memory (after release): " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("Memory increase: " + (memoryIncrease / 1024 / 1024) + " MB");
        System.out.println("Memory after release: " + (memoryAfterRelease / 1024 / 1024) + " MB");

        pool.close();

        // Memory usage should be reasonable
        assertTrue("Memory increase should be less than 50MB", memoryIncrease < 50 * 1024 * 1024);
        assertTrue("Memory after release should be less than 10MB", memoryAfterRelease < 10 * 1024 * 1024);
    }

    @Test
    public void testConnectionLeakDetection() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 10, 10);

        // Acquire all connections and don't release them
        List<java.sql.Connection> connections = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            connections.add(pool.getConnection().getConnection());
        }

        PoolStatistics stats = pool.getPoolStatistics();
        assertEquals("All connections should be active", 10, stats.activeConnections);
        assertEquals("No connections should be recycled", 0, stats.recycledConnections);
        assertEquals("No permits should be available", 0, stats.availablePermits);

        // Try to get another connection (should timeout)
        long startTime = System.currentTimeMillis();
        try {
            pool.getConnection().getConnection();
            fail("Should have timed out");
        } catch (javaxt.sql.ConnectionPool.TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            assertTrue("Should timeout within reasonable time", elapsed >= 9000 && elapsed <= 11000);
        }

        // Release all connections
        for (java.sql.Connection conn : connections) {
            conn.close();
        }

        stats = pool.getPoolStatistics();
        assertEquals("All connections should be recycled", 10, stats.recycledConnections);
        assertEquals("No connections should be active", 0, stats.activeConnections);
        assertEquals("All permits should be used by recycled connections", 0, stats.availablePermits);

        pool.close();
    }

    // ==================== STRESS TESTS ====================

    @Test
    public void testHighLoadStress() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, 20, 5);

        int numThreads = 100;
        int operationsPerThread = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        printTestHeader("High Load Stress Test");
        System.out.println("Threads: " + numThreads);
        System.out.println("Operations per thread: " + operationsPerThread);

        long startTime = System.nanoTime();

        // Create many threads for stress testing
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            java.sql.Connection conn = pool.getConnection().getConnection();
                            try (PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                                stmt.setInt(1, (threadId * operationsPerThread + j) % 1000 + 1);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    rs.next();
                                }
                            }
                            conn.close();
                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        assertTrue("All threads should complete within 60 seconds",
                   finishLatch.await(60, TimeUnit.SECONDS));

        long endTime = System.nanoTime();

        int totalOps = successCount.get() + errorCount.get();
        int successOps = successCount.get();
        int errorOps = errorCount.get();
        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalOps / (totalTimeMs / 1000.0);
        double errorRate = (double) errorOps / totalOps * 100.0;

        System.out.println("Total operations: " + totalOps);
        System.out.println("Successful operations: " + successOps);
        System.out.println("Failed operations: " + errorOps);
        System.out.println("Error rate: " + String.format("%.2f", errorRate) + "%");
        System.out.println("Total time: " + String.format("%.3f", totalTimeMs) + " ms");
        System.out.println("Throughput: " + String.format("%.0f", throughput) + " ops/sec");

        // Optional HikariCP comparison
        if (shouldBenchmarkAgainstHikari()) {
            HikariDataSource hikariPool = createHikariPool(20, 5);

            CountDownLatch hikariStart = new CountDownLatch(1);
            CountDownLatch hikariFinish = new CountDownLatch(numThreads);
            AtomicInteger hikariSuccess = new AtomicInteger(0);
            AtomicInteger hikariErrors = new AtomicInteger(0);

            long hikariStartTime = System.nanoTime();

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                new Thread(() -> {
                    try {
                        hikariStart.await();
                        for (int j = 0; j < operationsPerThread; j++) {
                            try (Connection conn = hikariPool.getConnection();
                                 PreparedStatement stmt = conn.prepareStatement("SELECT data FROM perf_test WHERE id = ?")) {
                                stmt.setInt(1, (threadId * operationsPerThread + j) % 1000 + 1);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    rs.next();
                                }
                                hikariSuccess.incrementAndGet();
                            } catch (Exception e) {
                                hikariErrors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        hikariErrors.incrementAndGet();
                    } finally {
                        hikariFinish.countDown();
                    }
                }).start();
            }

            hikariStart.countDown();
            hikariFinish.await(60, TimeUnit.SECONDS);
            long hikariEndTime = System.nanoTime();

            double hikariTimeMs = (hikariEndTime - hikariStartTime) / 1_000_000.0;
            int hikariTotal = hikariSuccess.get() + hikariErrors.get();
            double hikariThroughput = hikariTotal / (hikariTimeMs / 1000.0);

            System.out.println("\n--- HikariCP Comparison ---");
            System.out.printf("HikariCP Throughput: %.0f ops/sec  (javaxt: %.0f ops/sec) - ", hikariThroughput, throughput);
            if (throughput > hikariThroughput) {
                System.out.printf("javaxt is %.1f%% FASTER under high load\n", ((throughput - hikariThroughput) / hikariThroughput * 100));
            } else {
                System.out.printf("HikariCP is %.1f%% faster under high load\n", ((hikariThroughput - throughput) / throughput * 100));
            }
            System.out.printf("HikariCP Errors:     %d (javaxt: %d)\n", hikariErrors.get(), errorOps);

            hikariPool.close();
        }

        pool.close();

        // Should have low error rate
        assertTrue("Error rate should be less than 5%", errorRate < 5.0);
        assertTrue("Should complete most operations", successOps > numThreads * operationsPerThread * 0.9);
    }
}
