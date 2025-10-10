package javaxt.core.test.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import javaxt.sql.ConnectionPool;
import org.junit.*;
import static org.junit.Assume.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Benchmark comparison between javaxt ConnectionPool and HikariCP.
 * This test provides informational performance metrics and does not fail on performance differences.
 *
 * <p>Tests run against all configured databases. Results show side-by-side comparison
 * of both connection pool implementations.</p>
 *
 * <p><strong>Note:</strong> This test is skipped by default to keep regular test runs fast.
 * To run HikariCP benchmarks, use: <code>mvn test -Dtest=ConnectionPoolBenchmarkTest</code></p>
 */
@RunWith(Parameterized.class)
public class ConnectionPoolBenchmarkTest {

    private ConnectionPoolTestConfig.DatabaseConfig dbConfig;
    private ConnectionPool javaxPool;
    private HikariDataSource hikariPool;

    /**
     * Provides test parameters - one set for each configured database.
     */
    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        // Skip all tests if benchmark is not explicitly enabled via -Dtest or -Dbenchmark.enabled
        // This allows: mvn test -Dtest=ConnectionPoolBenchmarkTest
        // Or: mvn test -Dbenchmark.enabled=true (runs with all other tests)
        boolean explicitlyRequested = System.getProperty("test") != null &&
                                      System.getProperty("test").contains("ConnectionPoolBenchmarkTest");
        boolean benchmarkEnabled = Boolean.parseBoolean(System.getProperty("benchmark.enabled", "false"));

        assumeTrue(
            "Skipping HikariCP benchmark tests (not enabled). Run with -Dtest=ConnectionPoolBenchmarkTest to enable.",
            explicitlyRequested || benchmarkEnabled
        );

        // Skip all tests if no database is configured
        assumeTrue(
            "Skipping benchmark tests: " + ConnectionPoolTestConfig.getGlobalError(),
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
    public ConnectionPoolBenchmarkTest(String displayName, ConnectionPoolTestConfig.DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Before
    public void setUp() throws Exception {
        // Initialize database with test data
        try (Connection conn = DriverManager.getConnection(
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword())) {
            conn.createStatement().execute("DROP TABLE IF EXISTS benchmark_test");
            conn.createStatement().execute("CREATE TABLE benchmark_test (id INT PRIMARY KEY, data VARCHAR(100))");

            // Insert test data
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO benchmark_test VALUES (?, ?)")) {
                for (int i = 1; i <= 1000; i++) {
                    stmt.setInt(1, i);
                    stmt.setString(2, "Test data " + i);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        if (javaxPool != null) {
            javaxPool.close();
        }
        if (hikariPool != null) {
            hikariPool.close();
        }
    }

    private void printHeader(String title) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  " + dbConfig.getType().getDisplayName() + " - " + title);
        System.out.println("=".repeat(80));
    }

    @Test
    public void benchmarkConnectionAcquisition() throws Exception {
        printHeader("Connection Acquisition Latency Benchmark");

        int samples = 1000;
        int poolSize = 20;

        // Benchmark javaxt ConnectionPool
        javaxPool = new ConnectionPool(dbConfig.getConnectionPoolDataSource(), poolSize, 10);
        long[] javaxLatencies = new long[samples];

        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            java.sql.Connection conn = javaxPool.getRawConnection();
            long end = System.nanoTime();
            javaxLatencies[i] = end - start;
            conn.close();
        }
        javaxPool.close();

        // Benchmark HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUser());
        config.setPassword(dbConfig.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize / 5);
        config.setConnectionTimeout(10000);
        hikariPool = new HikariDataSource(config);

        long[] hikariLatencies = new long[samples];

        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            Connection conn = hikariPool.getConnection();
            long end = System.nanoTime();
            hikariLatencies[i] = end - start;
            conn.close();
        }

        // Calculate statistics
        double javaxAvg = calculateAverage(javaxLatencies);
        double hikariAvg = calculateAverage(hikariLatencies);
        Arrays.sort(javaxLatencies);
        Arrays.sort(hikariLatencies);

        long javaxP50 = javaxLatencies[samples / 2];
        long javaxP95 = javaxLatencies[(int) (samples * 0.95)];
        long javaxP99 = javaxLatencies[(int) (samples * 0.99)];

        long hikariP50 = hikariLatencies[samples / 2];
        long hikariP95 = hikariLatencies[(int) (samples * 0.95)];
        long hikariP99 = hikariLatencies[(int) (samples * 0.99)];

        // Print comparison
        System.out.println("\nSamples: " + samples);
        System.out.println("\n                    javaxt          HikariCP        Difference");
        System.out.println("                    -------         ---------       ----------");
        System.out.printf("Average:            %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxAvg, hikariAvg, javaxAvg < hikariAvg ? "-" : "+",
            Math.abs(javaxAvg - hikariAvg), Math.abs((javaxAvg - hikariAvg) / hikariAvg * 100));
        System.out.printf("P50 (median):       %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxP50 / 1_000_000.0, hikariP50 / 1_000_000.0,
            javaxP50 < hikariP50 ? "-" : "+",
            Math.abs(javaxP50 - hikariP50) / 1_000_000.0,
            Math.abs((javaxP50 - hikariP50) / (double) hikariP50 * 100));
        System.out.printf("P95:                %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxP95 / 1_000_000.0, hikariP95 / 1_000_000.0,
            javaxP95 < hikariP95 ? "-" : "+",
            Math.abs(javaxP95 - hikariP95) / 1_000_000.0,
            Math.abs((javaxP95 - hikariP95) / (double) hikariP95 * 100));
        System.out.printf("P99:                %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxP99 / 1_000_000.0, hikariP99 / 1_000_000.0,
            javaxP99 < hikariP99 ? "-" : "+",
            Math.abs(javaxP99 - hikariP99) / 1_000_000.0,
            Math.abs((javaxP99 - hikariP99) / (double) hikariP99 * 100));

        if (javaxAvg < hikariAvg) {
            System.out.printf("\n✓ javaxt ConnectionPool is %.1f%% FASTER than HikariCP!\n",
                ((hikariAvg - javaxAvg) / hikariAvg * 100));
        } else {
            System.out.printf("\n✓ HikariCP is %.1f%% faster than javaxt ConnectionPool\n",
                ((javaxAvg - hikariAvg) / javaxAvg * 100));
        }
    }

    @Test
    public void benchmarkConcurrentThroughput() throws Exception {
        printHeader("Concurrent Throughput Benchmark");

        int numThreads = 20;
        int operationsPerThread = 100;
        int poolSize = 20;

        // Benchmark javaxt ConnectionPool
        javaxPool = new ConnectionPool(dbConfig.getConnectionPoolDataSource(), poolSize, 10);
        long javaxTime = benchmarkPoolThroughput(javaxPool, numThreads, operationsPerThread);
        javaxPool.close();

        // Benchmark HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUser());
        config.setPassword(dbConfig.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize / 5);
        config.setConnectionTimeout(10000);
        hikariPool = new HikariDataSource(config);

        long hikariTime = benchmarkDataSourceThroughput(hikariPool, numThreads, operationsPerThread);

        // Calculate throughput
        int totalOps = numThreads * operationsPerThread;
        double javaxThroughput = totalOps / (javaxTime / 1_000_000_000.0);
        double hikariThroughput = totalOps / (hikariTime / 1_000_000_000.0);

        // Print comparison
        System.out.println("\nThreads: " + numThreads);
        System.out.println("Operations per thread: " + operationsPerThread);
        System.out.println("Total operations: " + totalOps);
        System.out.println("\n                    javaxt          HikariCP        Difference");
        System.out.println("                    -------         ---------       ----------");
        System.out.printf("Total time:         %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxTime / 1_000_000.0, hikariTime / 1_000_000.0,
            javaxTime < hikariTime ? "-" : "+",
            Math.abs(javaxTime - hikariTime) / 1_000_000.0,
            Math.abs((javaxTime - hikariTime) / (double) hikariTime * 100));
        System.out.printf("Throughput:         %.0f ops/sec   %.0f ops/sec   %s%.0f ops/sec (%.1f%%)\n",
            javaxThroughput, hikariThroughput,
            javaxThroughput > hikariThroughput ? "+" : "",
            javaxThroughput - hikariThroughput,
            Math.abs((javaxThroughput - hikariThroughput) / hikariThroughput * 100));

        if (javaxThroughput > hikariThroughput) {
            System.out.printf("\n✓ javaxt ConnectionPool is %.1f%% FASTER than HikariCP!\n",
                ((javaxThroughput - hikariThroughput) / hikariThroughput * 100));
        } else {
            System.out.printf("\n✓ HikariCP is %.1f%% faster than javaxt ConnectionPool\n",
                ((hikariThroughput - javaxThroughput) / javaxThroughput * 100));
        }
    }

    @Test
    public void benchmarkHighLoadStress() throws Exception {
        printHeader("High Load Stress Test Benchmark");

        int numThreads = 100;
        int operationsPerThread = 50;
        int poolSize = 20;

        // Benchmark javaxt ConnectionPool
        javaxPool = new ConnectionPool(dbConfig.getConnectionPoolDataSource(), poolSize, 30);
        BenchmarkResult javaxResult = stressTest(javaxPool, numThreads, operationsPerThread);
        javaxPool.close();

        // Benchmark HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUser());
        config.setPassword(dbConfig.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize / 5);
        config.setConnectionTimeout(30000);
        hikariPool = new HikariDataSource(config);

        BenchmarkResult hikariResult = stressTestDataSource(hikariPool, numThreads, operationsPerThread);

        // Print comparison
        System.out.println("\nThreads: " + numThreads);
        System.out.println("Operations per thread: " + operationsPerThread);
        System.out.println("Total operations: " + (numThreads * operationsPerThread));
        System.out.println("\n                    javaxt          HikariCP        Difference");
        System.out.println("                    -------         ---------       ----------");
        System.out.printf("Total time:         %.3f ms        %.3f ms        %s%.3f ms (%.1f%%)\n",
            javaxResult.timeMs, hikariResult.timeMs,
            javaxResult.timeMs < hikariResult.timeMs ? "-" : "+",
            Math.abs(javaxResult.timeMs - hikariResult.timeMs),
            Math.abs((javaxResult.timeMs - hikariResult.timeMs) / hikariResult.timeMs * 100));
        System.out.printf("Throughput:         %.0f ops/sec   %.0f ops/sec   %s%.0f ops/sec (%.1f%%)\n",
            javaxResult.throughput, hikariResult.throughput,
            javaxResult.throughput > hikariResult.throughput ? "+" : "",
            javaxResult.throughput - hikariResult.throughput,
            Math.abs((javaxResult.throughput - hikariResult.throughput) / hikariResult.throughput * 100));
        System.out.printf("Success rate:       %.2f%%          %.2f%%\n",
            javaxResult.successRate, hikariResult.successRate);

        if (javaxResult.throughput > hikariResult.throughput) {
            System.out.printf("\n✓ javaxt ConnectionPool is %.1f%% FASTER than HikariCP under high load!\n",
                ((javaxResult.throughput - hikariResult.throughput) / hikariResult.throughput * 100));
        } else {
            System.out.printf("\n✓ HikariCP is %.1f%% faster than javaxt ConnectionPool under high load\n",
                ((hikariResult.throughput - javaxResult.throughput) / javaxResult.throughput * 100));
        }
    }

    @Test
    public void benchmarkPoolScalability() throws Exception {
        printHeader("Pool Size Scalability Benchmark");

        int[] poolSizes = {5, 10, 20, 50};
        int operations = 500;

        System.out.println("\nOperations per test: " + operations);
        System.out.println("\nPool Size    javaxt          HikariCP        Winner");
        System.out.println("---------    -------         ---------       ------");

        int javaxWins = 0;
        int hikariWins = 0;

        for (int poolSize : poolSizes) {
            // Benchmark javaxt
            javaxPool = new ConnectionPool(dbConfig.getConnectionPoolDataSource(), poolSize, 10);
            long javaxTime = benchmarkPoolOperations(javaxPool, operations);
            double javaxThroughput = operations / (javaxTime / 1_000_000_000.0);
            javaxPool.close();

            // Benchmark HikariCP
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(dbConfig.getUrl());
            config.setUsername(dbConfig.getUser());
            config.setPassword(dbConfig.getPassword());
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(Math.max(1, poolSize / 5));
            config.setConnectionTimeout(10000);
            hikariPool = new HikariDataSource(config);

            long hikariTime = benchmarkDataSourceOperations(hikariPool, operations);
            double hikariThroughput = operations / (hikariTime / 1_000_000_000.0);
            hikariPool.close();

            String winner = javaxThroughput > hikariThroughput ? "javaxt" : "HikariCP";
            if (javaxThroughput > hikariThroughput) javaxWins++;
            else hikariWins++;

            System.out.printf("%-12d %.0f ops/sec     %.0f ops/sec     %s (%.1f%% faster)\n",
                poolSize, javaxThroughput, hikariThroughput, winner,
                Math.abs((javaxThroughput - hikariThroughput) / Math.min(javaxThroughput, hikariThroughput) * 100));
        }

        System.out.println("\nScalability Summary:");
        System.out.printf("  javaxt wins:   %d/%d pool sizes\n", javaxWins, poolSizes.length);
        System.out.printf("  HikariCP wins: %d/%d pool sizes\n", hikariWins, poolSizes.length);
    }

    @Test
    public void benchmarkOverallSummary() throws Exception {
        printHeader("OVERALL BENCHMARK SUMMARY");

        int poolSize = 20;
        int iterations = 500;

        // Create both pools
        javaxPool = new ConnectionPool(dbConfig.getConnectionPoolDataSource(), poolSize, 10);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUser());
        config.setPassword(dbConfig.getPassword());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize / 5);
        config.setConnectionTimeout(10000);
        hikariPool = new HikariDataSource(config);

        System.out.println("\nTest Configuration:");
        System.out.println("  Pool size: " + poolSize);
        System.out.println("  Test iterations: " + iterations);
        System.out.println();

        // Test 1: Simple connection acquisition
        double javaxSimple = benchmarkSimpleAcquisition(javaxPool, iterations);
        double hikariSimple = benchmarkSimpleAcquisition(hikariPool, iterations);

        // Test 2: With query execution
        double javaxQuery = benchmarkWithQuery(javaxPool, iterations);
        double hikariQuery = benchmarkWithQuery(hikariPool, iterations);

        // Test 3: Concurrent access
        double javaxConcurrent = benchmarkConcurrent(javaxPool, 10, iterations / 10);
        double hikariConcurrent = benchmarkConcurrent(hikariPool, 10, iterations / 10);

        // Print results
        System.out.println("Test                        javaxt      HikariCP    Winner");
        System.out.println("----                        -------     ---------   ------");
        System.out.printf("Simple Acquisition:         %.0f/s      %.0f/s      %s (%.1f%% faster)\n",
            javaxSimple, hikariSimple,
            javaxSimple > hikariSimple ? "javaxt" : "HikariCP",
            Math.abs((javaxSimple - hikariSimple) / Math.min(javaxSimple, hikariSimple) * 100));
        System.out.printf("With Query Execution:       %.0f/s      %.0f/s      %s (%.1f%% faster)\n",
            javaxQuery, hikariQuery,
            javaxQuery > hikariQuery ? "javaxt" : "HikariCP",
            Math.abs((javaxQuery - hikariQuery) / Math.min(javaxQuery, hikariQuery) * 100));
        System.out.printf("Concurrent Access (10t):    %.0f/s      %.0f/s      %s (%.1f%% faster)\n",
            javaxConcurrent, hikariConcurrent,
            javaxConcurrent > hikariConcurrent ? "javaxt" : "HikariCP",
            Math.abs((javaxConcurrent - hikariConcurrent) / Math.min(javaxConcurrent, hikariConcurrent) * 100));

        int javaxWins = 0;
        int hikariWins = 0;
        if (javaxSimple > hikariSimple) javaxWins++; else hikariWins++;
        if (javaxQuery > hikariQuery) javaxWins++; else hikariWins++;
        if (javaxConcurrent > hikariConcurrent) javaxWins++; else hikariWins++;

        System.out.println("\n" + "=".repeat(80));
        System.out.printf("FINAL SCORE:  javaxt %d  -  HikariCP %d\n", javaxWins, hikariWins);
        System.out.println("=".repeat(80));

        if (javaxWins > hikariWins) {
            System.out.println("\n🏆 javaxt ConnectionPool WINS on " + dbConfig.getType().getDisplayName() + "!");
        } else if (hikariWins > javaxWins) {
            System.out.println("\n🏆 HikariCP wins on " + dbConfig.getType().getDisplayName() + "!");
        } else {
            System.out.println("\n🤝 TIE - Both pools perform equally well on " + dbConfig.getType().getDisplayName() + "!");
        }
    }

    // ==================== HELPER METHODS ====================

    private double calculateAverage(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum / (double) values.length / 1_000_000.0;
    }

    private long benchmarkPoolThroughput(ConnectionPool pool, int numThreads, int operationsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);

        long startTime = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        java.sql.Connection conn = pool.getRawConnection();
                        try (Statement stmt = conn.createStatement()) {
                            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM benchmark_test WHERE id <= 100")) {
                                rs.next();
                            }
                        }
                        conn.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        return System.nanoTime() - startTime;
    }

    private long benchmarkDataSourceThroughput(DataSource dataSource, int numThreads, int operationsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);

        long startTime = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        try (Connection conn = dataSource.getConnection();
                             Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM benchmark_test WHERE id <= 100")) {
                            rs.next();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        return System.nanoTime() - startTime;
    }

    private BenchmarkResult stressTest(ConnectionPool pool, int numThreads, int operationsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            java.sql.Connection conn = pool.getRawConnection();
                            try (Statement stmt = conn.createStatement()) {
                                try (ResultSet rs = stmt.executeQuery("SELECT data FROM benchmark_test WHERE id = " + ((j % 1000) + 1))) {
                                    if (rs.next()) {
                                        successCount.incrementAndGet();
                                    }
                                }
                            }
                            conn.close();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        long endTime = System.nanoTime();

        int totalOps = numThreads * operationsPerThread;
        double timeMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalOps / (timeMs / 1000.0);
        double successRate = (successCount.get() / (double) totalOps) * 100;

        return new BenchmarkResult(timeMs, throughput, successRate);
    }

    private BenchmarkResult stressTestDataSource(DataSource dataSource, int numThreads, int operationsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        try (Connection conn = dataSource.getConnection();
                             Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT data FROM benchmark_test WHERE id = " + ((j % 1000) + 1))) {
                            if (rs.next()) {
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        long endTime = System.nanoTime();

        int totalOps = numThreads * operationsPerThread;
        double timeMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalOps / (timeMs / 1000.0);
        double successRate = (successCount.get() / (double) totalOps) * 100;

        return new BenchmarkResult(timeMs, throughput, successRate);
    }

    private long benchmarkPoolOperations(ConnectionPool pool, int operations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            try {
                java.sql.Connection conn = pool.getRawConnection();
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return System.nanoTime() - startTime;
    }

    private long benchmarkDataSourceOperations(DataSource dataSource, int operations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            try (Connection conn = dataSource.getConnection()) {
                // Just acquire and release
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return System.nanoTime() - startTime;
    }

    private double benchmarkSimpleAcquisition(ConnectionPool pool, int iterations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                java.sql.Connection conn = pool.getRawConnection();
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        long endTime = System.nanoTime();
        return iterations / ((endTime - startTime) / 1_000_000_000.0);
    }

    private double benchmarkSimpleAcquisition(DataSource dataSource, int iterations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (Connection conn = dataSource.getConnection()) {
                // Just acquire and release
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        long endTime = System.nanoTime();
        return iterations / ((endTime - startTime) / 1_000_000_000.0);
    }

    private double benchmarkWithQuery(ConnectionPool pool, int iterations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                java.sql.Connection conn = pool.getRawConnection();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id FROM benchmark_test WHERE id = " + ((i % 1000) + 1))) {
                    rs.next();
                }
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        long endTime = System.nanoTime();
        return iterations / ((endTime - startTime) / 1_000_000_000.0);
    }

    private double benchmarkWithQuery(DataSource dataSource, int iterations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id FROM benchmark_test WHERE id = " + ((i % 1000) + 1))) {
                rs.next();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        long endTime = System.nanoTime();
        return iterations / ((endTime - startTime) / 1_000_000_000.0);
    }

    private double benchmarkConcurrent(ConnectionPool pool, int threads, int opsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        long startTime = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        java.sql.Connection conn = pool.getRawConnection();
                        try (Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT 1")) {
                            rs.next();
                        }
                        conn.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        long endTime = System.nanoTime();

        int totalOps = threads * opsPerThread;
        return totalOps / ((endTime - startTime) / 1_000_000_000.0);
    }

    private double benchmarkConcurrent(DataSource dataSource, int threads, int opsPerThread) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        long startTime = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        try (Connection conn = dataSource.getConnection();
                             Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT 1")) {
                            rs.next();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        finishLatch.await();
        long endTime = System.nanoTime();

        int totalOps = threads * opsPerThread;
        return totalOps / ((endTime - startTime) / 1_000_000_000.0);
    }

    private static class BenchmarkResult {
        final double timeMs;
        final double throughput;
        final double successRate;

        BenchmarkResult(double timeMs, double throughput, double successRate) {
            this.timeMs = timeMs;
            this.throughput = throughput;
            this.successRate = successRate;
        }
    }
}

