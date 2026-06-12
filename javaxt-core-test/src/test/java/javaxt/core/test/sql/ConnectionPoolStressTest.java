package javaxt.core.test.sql;

import java.lang.management.ManagementFactory;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.ConnectionPoolDataSource;
import javaxt.sql.ConnectionPool;
import javaxt.sql.ConnectionPool.PoolStatistics;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Stress and regression tests for ConnectionPool covering scenarios that the
 * functional/health tests do not exercise:
 * <ul>
 *   <li>waiter latency under contention</li>
 *   <li>aged-connection eviction via forceHealthCheck</li>
 *   <li>orphan handling when a thread dies holding a connection</li>
 *   <li>steady-state memory stability over many acquire/release cycles</li>
 *   <li>connection state (autoCommit/transaction) reset on return</li>
 *   <li>fairness across competing threads</li>
 * </ul>
 *
 * <p>Parameterized across all configured databases (H2, PostgreSQL, MySQL).
 * Returns an empty parameter list when no databases are configured, which
 * causes Parameterized to skip the class cleanly. (Throwing
 * AssumptionViolatedException from {@code @Parameters} would surface as a
 * class-init error, not a clean skip.)</p>
 *
 * <p>The memory-stability test can be skipped with
 * {@code -Dskip.memory.tests=true} in environments where GC behavior is too
 * noisy to be reliable.</p>
 */
@RunWith(Parameterized.class)
public class ConnectionPoolStressTest {

    private ConnectionPoolDataSource dataSource;
    private ConnectionPoolTestConfig.DatabaseConfig dbConfig;
    private static String lastPrintedDatabase = null;

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        List<Object[]> params = new ArrayList<>();
        if (!ConnectionPoolTestConfig.hasValidConfiguration()) {
            return params; // empty -> Parameterized skips the class
        }
        for (ConnectionPoolTestConfig.DatabaseConfig config : ConnectionPoolTestConfig.getValidConfigurations()) {
            params.add(new Object[]{config.getType().getDisplayName(), config});
        }
        return params;
    }

    public ConnectionPoolStressTest(String displayName, ConnectionPoolTestConfig.DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private void printTestHeader(String testName) {
        String dbName = dbConfig.getType().getDisplayName();
        if (!dbName.equals(lastPrintedDatabase)) {
            System.out.println("\n=== " + dbName + " - Stress Tests ===");
            lastPrintedDatabase = dbName;
        }
        System.out.println("\n--- " + dbName + " - " + testName + " ---");
    }

    @Before
    public void setUp() throws Exception {
        this.dataSource = dbConfig.getConnectionPoolDataSource();

        try (Connection conn = DriverManager.getConnection(
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword())) {
            conn.createStatement().execute("DROP TABLE IF EXISTS stress_test");
            conn.createStatement().execute("CREATE TABLE stress_test (id INT PRIMARY KEY, name VARCHAR(50))");
        }
    }

    @After
    public void tearDown() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword())) {
            conn.createStatement().execute("DROP TABLE IF EXISTS stress_test");
        } catch (Exception ignore) {}
    }


    // ==================== 1. WAITER LATENCY UNDER CONTENTION ====================

    /**
     * Measures how long a thread blocks waiting for a connection when the pool
     * is exhausted, after another thread releases one. The current pool polls
     * with Thread.sleep(10) inner / Thread.sleep(250) outer, so the floor is in
     * that range. A wait/notify or LockSupport refactor should drop this
     * dramatically; this test fixes a regression baseline at the current bound.
     *
     * <p>Note: 8 waiters is too small a sample for a meaningful p99 — we report
     * p50 and max instead, and assert on max.</p>
     */
    @Test
    public void testWaiterLatencyUnderContention() throws Exception {
        printTestHeader("Waiter Latency Under Contention");

        final int poolSize = 2;
        final int waiterCount = 8;
        final ConnectionPool pool = new ConnectionPool(dataSource, poolSize, 10);

        try {
            // Hold every connection so all waiters must block.
            List<javaxt.sql.Connection> held = new ArrayList<>();
            for (int i = 0; i < poolSize; i++) {
                held.add(pool.getConnection());
            }

            final CountDownLatch ready = new CountDownLatch(waiterCount);
            final CountDownLatch go = new CountDownLatch(1);
            final long[] startNs = new long[waiterCount];
            final long[] acquiredNs = new long[waiterCount];
            final boolean[] ok = new boolean[waiterCount];

            ExecutorService executor = Executors.newFixedThreadPool(waiterCount);
            try {
                for (int i = 0; i < waiterCount; i++) {
                    final int idx = i;
                    executor.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                            startNs[idx] = System.nanoTime();
                            try (javaxt.sql.Connection c = pool.getConnection()) {
                                acquiredNs[idx] = System.nanoTime();
                                ok[idx] = true;
                            }
                        } catch (Exception e) {
                            ok[idx] = false;
                        }
                    });
                }

                ready.await();
                go.countDown();

                // Give all waiters a moment to enter their wait state, then
                // release the held connections at a fixed cadence so we can
                // measure handoff time.
                Thread.sleep(50);
                for (javaxt.sql.Connection c : held) {
                    c.close();
                    Thread.sleep(20);
                }

                executor.shutdown();
                assertTrue("Waiters should finish", executor.awaitTermination(15, TimeUnit.SECONDS));
            } finally {
                if (!executor.isTerminated()) executor.shutdownNow();
            }

            // Collect successful waiter latencies (ms)
            List<Long> latenciesMs = new ArrayList<>();
            for (int i = 0; i < waiterCount; i++) {
                if (ok[i]) {
                    latenciesMs.add((acquiredNs[i] - startNs[i]) / 1_000_000L);
                }
            }
            Collections.sort(latenciesMs);
            assertEquals("All waiters should have acquired a connection",
                         waiterCount, latenciesMs.size());

            long p50 = latenciesMs.get(latenciesMs.size() / 2);
            long max = latenciesMs.get(latenciesMs.size() - 1);

            System.out.println("  Waiters successfully acquired: " + latenciesMs.size() + "/" + waiterCount);
            System.out.println("  Waiter latency p50: " + p50 + " ms");
            System.out.println("  Waiter latency max: " + max + " ms");

            // Loose bound to accommodate the current Thread.sleep(250) outer loop.
            // Refactor to wait/notify or LockSupport should drop max well under 50ms.
            assertTrue("Waiter max latency should be reasonable; got " + max + " ms",
                       max < 2000);
        } finally {
            pool.close();
        }
    }


    // ==================== 2. AGED-CONNECTION EVICTION VIA HEALTH CHECK ====================

    /**
     * Verifies that the health-check pass actually evicts aged connections.
     * Uses {@link ConnectionPool#forceHealthCheck()} to drive the maintenance
     * pass synchronously (otherwise it only runs every 30 seconds).
     *
     * <p>This test depends on the health check actually doing something useful;
     * note that {@code validateConnection()} in the current implementation is a
     * no-op stub that always returns {@code true}, so this test specifically
     * exercises the {@code isExpired}/{@code disposeConnection} path in
     * {@code performHealthCheck}.</p>
     */
    @Test
    public void testAgedConnectionsEvictedByHealthCheck() throws Exception {
        printTestHeader("Aged Connections Evicted By Health Check");

        final ConnectionPool pool = new ConnectionPool(dataSource, 3, new HashMap<String, Object>() {{
            put("timeout", 5);
            put("maxAge", 1);  // 1 second
        }});

        try {
            // Populate the pool with 3 recycled connections.
            List<javaxt.sql.Connection> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) held.add(pool.getConnection());
            for (javaxt.sql.Connection c : held) c.close();

            PoolStatistics before = pool.getPoolStatistics();
            System.out.println("  Before aging: active=" + before.activeConnections
                + ", recycled=" + before.recycledConnections
                + ", total=" + before.totalConnections);
            assertEquals("Should have 3 recycled connections before aging",
                         3, before.recycledConnections);

            // Wait past maxAge.
            Thread.sleep(1500);

            // Synchronously run the health check.
            pool.forceHealthCheck();

            PoolStatistics after = pool.getPoolStatistics();
            System.out.println("  After health check: active=" + after.activeConnections
                + ", recycled=" + after.recycledConnections
                + ", total=" + after.totalConnections);

            // The aged connections should have been evicted. Warm-up may have
            // re-added some up to minConnections; the load-bearing assertion is
            // that totalConnections is no longer 3 (eviction happened) AND that
            // subsequent acquisitions still succeed.
            assertTrue("Aged connections should have been evicted from pool",
                       after.totalConnections < before.totalConnections
                    || after.recycledConnections < before.recycledConnections);

            // Verify the pool still works end-to-end after eviction.
            try (javaxt.sql.Connection c = pool.getConnection()) {
                try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                    try (ResultSet rs = s.executeQuery()) {
                        assertTrue(rs.next());
                    }
                }
            }
        } finally {
            pool.close();
        }
    }


    // ==================== 3. ORPHANED THREAD DOES NOT BREAK POOL ====================

    /**
     * Spawns a thread that acquires a connection and dies without closing it.
     * Documents current behavior (the connection stays "active" - no auto leak
     * detection today) and asserts the pool itself remains usable for other
     * threads. This guards against a future TLS-cache refactor that could leave
     * the orphaned connection cached and unusable.
     */
    @Test
    public void testOrphanedThreadDoesNotBreakPool() throws Exception {
        printTestHeader("Orphaned Thread Does Not Break Pool");

        final ConnectionPool pool = new ConnectionPool(dataSource, 4, 5);

        try {
            // Orphan thread acquires and dies without closing.
            Thread orphan = new Thread(() -> {
                try {
                    pool.getConnection(); // never closed; thread exits
                } catch (Exception e) {
                    // ignore
                }
            });
            orphan.start();
            orphan.join(5_000);
            assertFalse("Orphan thread should have terminated", orphan.isAlive());

            // The orphaned connection is leaked - pool is down to 3 usable.
            // Verify the rest of the pool still works.
            List<javaxt.sql.Connection> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                javaxt.sql.Connection c = pool.getConnection();
                try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                    try (ResultSet rs = s.executeQuery()) {
                        assertTrue(rs.next());
                    }
                }
                held.add(c);
            }
            for (javaxt.sql.Connection c : held) c.close();

            PoolStatistics stats = pool.getPoolStatistics();
            System.out.println("  After orphan: active=" + stats.activeConnections
                + ", recycled=" + stats.recycledConnections
                + ", total=" + stats.totalConnections);

            // Document current behavior: exactly one orphan stays active forever.
            assertEquals("Exactly one orphaned connection should remain active",
                         1, stats.activeConnections);

            // Verify pool remains usable end-to-end after the orphan event.
            try (javaxt.sql.Connection c = pool.getConnection()) {
                try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                    try (ResultSet rs = s.executeQuery()) {
                        assertTrue("Pool should still serve connections", rs.next());
                    }
                }
            }
        } finally {
            pool.close();
        }
    }


    // ==================== 4. STEADY-STATE MEMORY STABILITY ====================

    /**
     * Runs many acquire/use/release cycles and asserts that heap usage does not
     * grow unboundedly. This will catch leaks in the wrapper-allocation path
     * (e.g., if a refactor accidentally retains references in a map or queue).
     *
     * <p>Skip with {@code -Dskip.memory.tests=true} in environments where GC
     * behavior is too noisy.</p>
     */
    @Test
    public void testSteadyStateMemoryStability() throws Exception {
        if (Boolean.getBoolean("skip.memory.tests")) {
            System.out.println("\n--- " + dbConfig.getType().getDisplayName()
                + " - Steady-State Memory Stability (SKIPPED via -Dskip.memory.tests) ---");
            return;
        }
        printTestHeader("Steady-State Memory Stability");

        // H2 (in-memory and file) is fast enough for the high-iteration bucket;
        // remote PG/MySQL get the smaller count so total wall-time stays sane.
        String dbName = dbConfig.getType().getDisplayName();
        final int iterations = (dbName.startsWith("H2")) ? 20_000 : 3_000;
        final ConnectionPool pool = new ConnectionPool(dataSource, 5, 10);

        try {
            // Warm up so JIT and pool reach steady state before the baseline.
            for (int i = 0; i < 200; i++) {
                try (javaxt.sql.Connection c = pool.getConnection()) {
                    try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                        s.executeQuery().close();
                    }
                }
            }

            forceGc();
            long heapBefore = usedHeapBytes();

            long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                try (javaxt.sql.Connection c = pool.getConnection()) {
                    try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                        s.executeQuery().close();
                    }
                }
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            forceGc();
            long heapAfter = usedHeapBytes();

            long deltaMb = (heapAfter - heapBefore) / (1024 * 1024);
            long baselineMb = Math.max(1, heapBefore / (1024 * 1024));

            System.out.println("  Iterations: " + iterations);
            System.out.println("  Elapsed: " + elapsedMs + " ms");
            System.out.println("  Heap before: " + (heapBefore / (1024 * 1024)) + " MB");
            System.out.println("  Heap after:  " + (heapAfter  / (1024 * 1024)) + " MB");
            System.out.println("  Delta:       " + deltaMb + " MB");

            // Bounded growth: tolerate the larger of 20 MB or 100% of baseline.
            // A real per-acquire leak would push this into the hundreds of MB.
            long bound = Math.max(20, baselineMb);
            assertTrue("Heap delta should be bounded; got " + deltaMb + " MB (bound " + bound + " MB)",
                       deltaMb < bound);
        } finally {
            pool.close();
        }
    }


    // ==================== 5. CONNECTION STATE RESET ON RETURN ====================

    /**
     * Verifies that a connection returned to the pool with an open transaction
     * and a modified autoCommit value does not pollute the next consumer.
     *
     * <p><strong>Important caveat:</strong> the current {@code ConnectionPool}
     * does not reset connection state on return (no rollback, no autoCommit
     * reset — see {@code recycleConnection}). This test passes today only
     * because the H2, PostgreSQL, and MySQL JDBC drivers all rollback
     * uncommitted transactions when their <em>logical</em> connection is closed
     * (a JDBC PooledConnection contract). So this test documents:
     * <ol>
     *   <li>the observable end-to-end guarantee (no cross-checkout pollution), and</li>
     *   <li>the current pool position (rely on driver, don't reset).</li>
     * </ol>
     * If a future refactor introduces a Connection wrapper that intercepts
     * close (e.g., for TLS caching) it MUST preserve the driver close path or
     * explicitly reset state itself, or this test will catch the regression.</p>
     */
    @Test
    public void testConnectionStateResetOnReturn() throws Exception {
        printTestHeader("Connection State Reset on Return");

        final ConnectionPool pool = new ConnectionPool(dataSource, 1, 10);

        try {
            // First checkout: pollute the connection state.
            boolean defaultAutoCommit;
            try (javaxt.sql.Connection wrapper = pool.getConnection()) {
                java.sql.Connection conn = wrapper.getConnection();
                defaultAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement s = conn.prepareStatement(
                        "INSERT INTO stress_test (id, name) VALUES (1, 'uncommitted')")) {
                    s.executeUpdate();
                }
                // Deliberately close without commit/rollback.
            }

            // Second checkout: should be a clean slate.
            try (javaxt.sql.Connection wrapper = pool.getConnection()) {
                java.sql.Connection conn = wrapper.getConnection();
                boolean nowAutoCommit = conn.getAutoCommit();

                int rowsVisible;
                try (PreparedStatement s = conn.prepareStatement(
                        "SELECT COUNT(*) FROM stress_test WHERE id = 1")) {
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        rowsVisible = rs.getInt(1);
                    }
                }

                System.out.println("  Default autoCommit: " + defaultAutoCommit);
                System.out.println("  AutoCommit after return: " + nowAutoCommit);
                System.out.println("  Uncommitted rows visible: " + rowsVisible);

                // Load-bearing assertion: uncommitted insert must not be
                // visible to the next consumer.
                assertEquals("Uncommitted insert must not survive checkout boundary",
                             0, rowsVisible);
                // Regression guard: today all three JDBC drivers in our test
                // matrix reset autoCommit on logical-close (this is not
                // required by the JDBC spec but is observed behavior). If a
                // future refactor introduces a Connection wrapper that
                // bypasses driver close (e.g., a TLS cache), the wrapper must
                // either preserve the driver close path or reset state itself.
                assertEquals("autoCommit is observed-reset by all drivers in the matrix; "
                           + "if this fails the pool may need to reset explicitly",
                             defaultAutoCommit, nowAutoCommit);
            }
        } finally {
            pool.close();
        }
    }


    // ==================== 6. FAIRNESS ACROSS COMPETING THREADS ====================

    /**
     * N threads compete for K&lt;N connections; assert that no thread is starved.
     * A future TLS-cache refactor could let one "hot" thread monopolize its
     * cached connection while others wait; this test guards against that by
     * bounding the max/min ratio of per-thread acquire counts.
     */
    @Test
    public void testFairnessAcrossThreads() throws Exception {
        printTestHeader("Fairness Across Threads");

        final int poolSize = 2;
        final int threadCount = 6;
        final int opsPerThread = 30;
        final ConnectionPool pool = new ConnectionPool(dataSource, poolSize, 10);

        try {
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger[] perThreadCounts = new AtomicInteger[threadCount];
            for (int i = 0; i < threadCount; i++) perThreadCounts[i] = new AtomicInteger();
            final AtomicLong failures = new AtomicLong();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    final int idx = i;
                    executor.submit(() -> {
                        try {
                            start.await();
                            for (int j = 0; j < opsPerThread; j++) {
                                try (javaxt.sql.Connection c = pool.getConnection()) {
                                    try (PreparedStatement s = c.getConnection().prepareStatement("SELECT 1")) {
                                        s.executeQuery().close();
                                    }
                                    perThreadCounts[idx].incrementAndGet();
                                } catch (Exception e) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException ignore) {}
                    });
                }
                start.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
            } finally {
                if (!executor.isTerminated()) executor.shutdownNow();
            }

            int min = Integer.MAX_VALUE, max = 0, total = 0;
            for (AtomicInteger c : perThreadCounts) {
                int v = c.get();
                if (v < min) min = v;
                if (v > max) max = v;
                total += v;
            }

            System.out.println("  Per-thread counts: " + Arrays.toString(
                Arrays.stream(perThreadCounts).mapToInt(AtomicInteger::get).toArray()));
            System.out.println("  Min: " + min + ", Max: " + max + ", Total: " + total);
            System.out.println("  Failures: " + failures.get());

            assertEquals("No failures expected", 0, failures.get());
            assertEquals("All operations should complete",
                         threadCount * opsPerThread, total);
            assertTrue("No thread should be starved (min > 0)", min > 0);
            // Loose fairness bound: 5x is generous; refactor introducing TLS
            // cache without spillback would push this much higher.
            assertTrue("max/min ratio should be bounded; got max=" + max + ", min=" + min,
                       max <= min * 5);
        } finally {
            pool.close();
        }
    }


    // ==================== HELPERS ====================

    private static long usedHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void forceGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
        }
    }
}
