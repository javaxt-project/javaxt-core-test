package javaxt.core.test.io;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import javaxt.io.Directory;


/**
 * Throughput / memory test for the Linux inotify watcher. Disabled by
 * default because it generates ~4k events and takes ~10s. Enable with:
 *
 *   mvn test -DrunStressTests=true ...
 *
 * Verifies that 2000 file creates followed by 2000 deletes all surface
 * through Directory.getEvents() with no inotify queue overflow, and that
 * the events queue is consistent.
 */
public class DirectoryWatcherStressTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Directory dir;
    private List<Directory.Event> events;


    @Before
    public void requireStressMode() {
        assumeTrue("INotify path is Linux-only",
            DirectoryWatcherTest.isLinux());
        assumeTrue("Stress tests off by default (use -DrunStressTests=true)",
            "true".equalsIgnoreCase(System.getProperty("runStressTests")));
    }


    @After
    public void tearDown() {
        if (dir != null) {
            try { dir.stop(); }
            catch (Exception ignore) {}
            dir = null;
        }
    }


  //**************************************************************************
  //** testTwoThousandCreatesAndDeletes
  //**************************************************************************
  /** 20 fresh subdirs × 100 files each = 2020 expected Creates and 2020
   *  expected Deletes. Verifies inotify never overflows, no events are
   *  silently dropped under load, and per-dir Create counts are clean
   *  (race-window dedup working).
   */
    @Test
    @SuppressWarnings("unchecked")
    public void testTwoThousandCreatesAndDeletes() throws Exception {
        dir = new Directory(tmp.getRoot());
        events = (List<Directory.Event>) dir.getEvents();
        Thread.sleep(300);

      //==================== CREATE PHASE ====================
        int expectedDirs  = 20;
        int filesPerDir   = 100;
        int expectedFiles = expectedDirs * filesPerDir;

        for (int d = 0; d < expectedDirs; d++) {
            File sub = new File(tmp.getRoot(), "d" + d);
            assertTrue(sub.mkdir());
            for (int f = 0; f < filesPerDir; f++) {
                Files.write(new File(sub, "f" + f + ".txt").toPath(),
                    "x".getBytes());
            }
        }

      //Wait for the full Create wave to land. Generous timeout - addEvent
      //is fast (no subprocess) but 2020 events still take a moment.
        int expectedCreates = expectedDirs + expectedFiles;
        waitForActionCount("Create", expectedCreates, 30_000);

        List<Directory.Event> afterCreates = snapshot();
        int creates = countAction(afterCreates, "Create");
        int overflows = countAction(afterCreates, "Overflow");

        assertEquals("All 20 dirs + 2000 files reported as Create",
            expectedCreates, creates);
        assertEquals("No inotify queue overflow during create phase",
            0, overflows);

      //Per-directory check: every dir should contribute exactly filesPerDir
      //file-level Creates. A failure here means race-window dedup misfired.
        for (int d = 0; d < expectedDirs; d++) {
            String prefix = new File(tmp.getRoot(), "d" + d).getAbsolutePath()
                + File.separator + "f";
            int countForDir = 0;
            for (Directory.Event e : afterCreates) {
                if ("Create".equalsIgnoreCase(e.getAction())
                    && e.getFile() != null
                    && e.getFile().startsWith(prefix))
                {
                    countForDir++;
                }
            }
            assertEquals("d" + d + " file Create count",
                filesPerDir, countForDir);
        }

      //==================== DELETE PHASE ====================
        for (int d = 0; d < expectedDirs; d++) {
            deleteRecursive(new File(tmp.getRoot(), "d" + d));
        }

        int expectedTotal = expectedCreates + expectedCreates; // 2020 creates + 2020 deletes
        waitForActionCount("Delete", expectedCreates, 30_000);

        List<Directory.Event> afterDeletes = snapshot();
        int deletes  = countAction(afterDeletes, "Delete");
        int overflow = countAction(afterDeletes, "Overflow");

        assertEquals("All 20 dirs + 2000 files reported as Delete",
            expectedCreates, deletes);
        assertEquals("No inotify queue overflow across whole run",
            0, overflow);
    }


  //==========================================================================
  //  Helpers
  //==========================================================================

    private void waitForActionCount(String action, int expected, long timeoutMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (events) {
            while (countAction(events, action) < expected) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new AssertionError(
                        "Timed out waiting for " + expected + " " + action +
                        " events; saw " + countAction(events, action));
                }
                events.wait(Math.min(wait, 500));
            }
        }
    }


    private List<Directory.Event> snapshot() {
        synchronized (events) {
            return new ArrayList<Directory.Event>(events);
        }
    }


    private static int countAction(List<Directory.Event> list, String action) {
        int n = 0;
        for (Directory.Event e : list) {
            if (action.equalsIgnoreCase(e.getAction())) n++;
        }
        return n;
    }


    private static void deleteRecursive(File f) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        Files.delete(f.toPath());
    }
}
