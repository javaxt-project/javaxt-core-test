package javaxt.core.test.io;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import javaxt.io.Directory;


/**
 * Functional tests for the Linux inotify-backed FileSystemWatcher path that
 * Directory.getEvents() uses. The watcher's INotify implementation is
 * Linux-only; on Windows / macOS the implementation falls back to JNI or
 * polling and these tests are silently skipped.
 *
 * <p>Each test gets a clean temp directory via {@link TemporaryFolder} and
 * starts its own watcher; {@link #tearDown()} closes the watcher to release
 * the inotify fd.
 */
public class DirectoryWatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Directory dir;
    private List<Directory.Event> events;
    private File restorePerms;  // dir to restore permissions on in tearDown


    @Before
    public void requireLinux() {
        assumeTrue("INotify path is Linux-only", isLinux());
    }


    @After
    public void tearDown() {
        if (restorePerms != null && restorePerms.exists()) {
            try {
                Files.setPosixFilePermissions(restorePerms.toPath(),
                    EnumSet.of(PosixFilePermission.OWNER_READ,
                               PosixFilePermission.OWNER_WRITE,
                               PosixFilePermission.OWNER_EXECUTE));
            }
            catch (Exception ignore) {}
            restorePerms = null;
        }
        if (dir != null) {
            try { dir.stop(); }
            catch (Exception ignore) {}
            dir = null;
        }
    }


  //**************************************************************************
  //** testCreateModifyDelete
  //**************************************************************************
  /** Smoke: a single file's full lifecycle (create, modify, delete) produces
   *  the expected event types via Directory.getEvents().
   */
    @Test
    public void testCreateModifyDelete() throws Exception {
        startWatcher();

        File f = new File(tmp.getRoot(), "file.txt");
        Files.write(f.toPath(), "hello".getBytes());     // Create + Modify
        waitForEvent("Create", f.getAbsolutePath(), 3000);

        Files.write(f.toPath(), "world".getBytes());     // Modify
        Files.delete(f.toPath());                        // Delete
        waitForEvent("Delete", f.getAbsolutePath(), 3000);

        List<Directory.Event> snap = snapshot();
        assertTrue("saw Create for file.txt",
            contains(snap, "Create", f.getAbsolutePath()));
        assertTrue("saw Delete for file.txt",
            contains(snap, "Delete", f.getAbsolutePath()));
    }


  //**************************************************************************
  //** testNewSubdirRaceWindow
  //**************************************************************************
  /** mkdir + immediate file drop: the race-window walk should emit a synth
   *  Create for the dropped file, and the dedup map should suppress the
   *  inotify echo so we don't see the same Create twice.
   */
    @Test
    public void testNewSubdirRaceWindow() throws Exception {
        startWatcher();

        File newDir = new File(tmp.getRoot(), "newdir");
        assertTrue(newDir.mkdir());
        File insider = new File(newDir, "insider.txt");
        Files.write(insider.toPath(), "in".getBytes());

        waitForEvent("Create", newDir.getAbsolutePath(), 3000);
        waitForEvent("Create", insider.getAbsolutePath(), 3000);

      //insider.txt must appear exactly once - dedup catches the echo
        assertEquals("synth race-window dedup",
            1, count(snapshot(), "Create", insider.getAbsolutePath()));
    }


  //**************************************************************************
  //** testDeepMkdirRace
  //**************************************************************************
  /** mkdir -p a/b/c + drop file at the bottom: all three intermediate dirs
   *  should be registered, and the file should produce a synth Create.
   */
    @Test
    public void testDeepMkdirRace() throws Exception {
        startWatcher();

        File a = new File(tmp.getRoot(), "race/a/b");
        assertTrue(a.mkdirs());
        File quick = new File(a, "quick.txt");
        Files.write(quick.toPath(), "fast".getBytes());

        waitForEvent("Create", quick.getAbsolutePath(), 3000);

        List<Directory.Event> snap = snapshot();
        assertTrue("Create for race", contains(snap, "Create",
            new File(tmp.getRoot(), "race").getAbsolutePath()));
        assertTrue("Create for race/a", contains(snap, "Create",
            new File(tmp.getRoot(), "race/a").getAbsolutePath()));
        assertTrue("Create for race/a/b", contains(snap, "Create",
            new File(tmp.getRoot(), "race/a/b").getAbsolutePath()));
        assertTrue("Create for race/a/b/quick.txt",
            contains(snap, "Create", quick.getAbsolutePath()));
    }


  //**************************************************************************
  //** testRecursiveDelete
  //**************************************************************************
  /** rm -rf a populated subtree: every file AND every directory inside is
   *  reported as Delete.
   */
    @Test
    public void testRecursiveDelete() throws Exception {
        File subA = new File(tmp.getRoot(), "subA");
        File subB = new File(subA, "subB");
        assertTrue(subB.mkdirs());
        File f1 = new File(subA, "f1.txt");
        File f2 = new File(subB, "f2.txt");
        Files.write(f1.toPath(), "a".getBytes());
        Files.write(f2.toPath(), "b".getBytes());

        startWatcher();

        deleteRecursive(subA);

        waitForEvent("Delete", subA.getAbsolutePath(), 3000);

        List<Directory.Event> snap = snapshot();
        assertTrue("Delete f1", contains(snap, "Delete", f1.getAbsolutePath()));
        assertTrue("Delete f2", contains(snap, "Delete", f2.getAbsolutePath()));
        assertTrue("Delete subB", contains(snap, "Delete", subB.getAbsolutePath()));
        assertTrue("Delete subA", contains(snap, "Delete", subA.getAbsolutePath()));
    }


  //**************************************************************************
  //** testStopJoinsEventThread
  //**************************************************************************
  /** dir.stop() must not return until the event thread has finished
   *  draining whatever was in flight. We verify this by sampling the events
   *  queue size immediately after stop() returns, sleeping, and re-sampling.
   *  If join() worked, the two samples must match (no events can arrive
   *  after stop() returned).
   */
    @Test
    public void testStopJoinsEventThread() throws Exception {
        startWatcher();

      //Generate a burst so the event thread is busy when stop() lands
        for (int i = 0; i < 100; i++) {
            Files.write(new File(tmp.getRoot(), "f" + i + ".txt").toPath(),
                "x".getBytes());
        }
        Thread.sleep(50);

        long t0 = System.currentTimeMillis();
        dir.stop();
        long elapsed = System.currentTimeMillis() - t0;
        dir = null;  // prevent tearDown from double-stopping

        int sizeAtStop;
        synchronized (events) { sizeAtStop = events.size(); }

        Thread.sleep(500);

        int sizeLater;
        synchronized (events) { sizeLater = events.size(); }

        assertEquals("no events arrived after stop() returned",
            sizeAtStop, sizeLater);
        assertTrue("stop() returned within the 5s cap",
            elapsed < 5000);
    }


  //**************************************************************************
  //** testRegisterIsIdempotent
  //**************************************************************************
  /** INotify.register() must be a no-op when called twice on the same Path.
   *  Two code paths race to register the same directory in production -
   *  start()'s walk and handleCreate() when the kernel queued an
   *  ENTRY_CREATE before our walk reached it. The pathToKey dedup defense
   *  guarantees the second register() is short-circuited.
   *
   *  <p>NB: uses reflection. If field names inside INotify change, this
   *  test will fail loudly - which is the desired signal that the refactor
   *  needs an accompanying test update.
   */
    @Test
    public void testRegisterIsIdempotent() throws Exception {
      //Build a known-size tree (root + 5 subdirs + 5 nested = 11 directories)
        int expectedDirCount = 1;
        for (int i = 0; i < 5; i++) {
            File sub = new File(tmp.getRoot(), "sub" + i);
            File nested = new File(sub, "nested");
            assertTrue(nested.mkdirs());
            expectedDirCount += 2;
        }

        startWatcher();

        Object inotify = getINotify();
        assertNotNull("INotify instance should be active on Linux", inotify);

        Map<?,?> keyToDir  = (Map<?,?>) getField(inotify, "keyToDir");
        Map<?,?> pathToKey = (Map<?,?>) getField(inotify, "pathToKey");

        assertEquals("keyToDir size after start()",  expectedDirCount, keyToDir.size());
        assertEquals("pathToKey size after start()", expectedDirCount, pathToKey.size());

      //Re-register an existing path three times via reflection. Without the
      //pathToKey defense, each call would put() the (potentially same) key
      //into keyToDir - which is idempotent only because the JDK returns the
      //same WatchKey for the same Path. The defense protects against any
      //JDK quirks and lets us catch real regressions if they ever surface.
        Path victim = (Path) pathToKey.keySet().iterator().next();
        Method register = inotify.getClass().getDeclaredMethod("register", Path.class);
        register.setAccessible(true);
        register.invoke(inotify, victim);
        register.invoke(inotify, victim);
        register.invoke(inotify, victim);

        assertEquals("keyToDir size unchanged after 3 re-registers",
            expectedDirCount, keyToDir.size());
        assertEquals("pathToKey size unchanged after 3 re-registers",
            expectedDirCount, pathToKey.size());
    }


  //**************************************************************************
  //** testStartupSkipsUnreadableSubdir
  //**************************************************************************
  /** A single unreadable subdirectory in the tree must not abort the walk.
   *  SimpleFileVisitor's default postVisitDirectory rethrows any IOException
   *  encountered while listing - which would knock the watcher into the
   *  polling fallback. Our visitor overrides postVisitDirectory and
   *  visitFileFailed to silently skip, matching the prior getChildren()
   *  semantics.
   */
    @Test
    public void testStartupSkipsUnreadableSubdir() throws Exception {
        File readable = new File(tmp.getRoot(), "readable/inner");
        File unreadable = new File(tmp.getRoot(), "UNREADABLE");
        assertTrue(readable.mkdirs());
        assertTrue(unreadable.mkdir());

      //chmod 000 the bad subdir; tearDown will restore so TemporaryFolder
      //can clean up.
        restorePerms = unreadable;
        Files.setPosixFilePermissions(unreadable.toPath(),
            EnumSet.noneOf(PosixFilePermission.class));

        startWatcher();  // must not throw

      //Watcher should still detect events in the readable subtree
        File newFile = new File(readable, "new.txt");
        Files.write(newFile.toPath(), "x".getBytes());

        waitForEvent("Create", newFile.getAbsolutePath(), 3000);
    }


  //==========================================================================
  //  Helpers
  //==========================================================================

    @SuppressWarnings("unchecked")
    private void startWatcher() throws Exception {
        dir = new Directory(tmp.getRoot());
        events = (List<Directory.Event>) dir.getEvents();
      //Give FileSystemWatcher.run() → INotify.start() time to walk + register
        Thread.sleep(300);
    }


  /** Blocks up to timeoutMs waiting for an event matching action+path. */
    private void waitForEvent(String action, String absPath, long timeoutMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (events) {
            while (!contains(events, action, absPath)) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new AssertionError(
                        "Timed out waiting for " + action + " " + absPath +
                        " (saw " + events.size() + " events)");
                }
                events.wait(Math.min(wait, 250));
            }
        }
    }


    private List<Directory.Event> snapshot() {
        synchronized (events) {
            return new ArrayList<Directory.Event>(events);
        }
    }


    private static boolean contains(List<Directory.Event> list,
        String action, String path)
    {
        for (Directory.Event e : list) {
            if (action.equalsIgnoreCase(e.getAction()) && path.equals(e.getFile())) {
                return true;
            }
        }
        return false;
    }


    private static int count(List<Directory.Event> list,
        String action, String path)
    {
        int n = 0;
        for (Directory.Event e : list) {
            if (action.equalsIgnoreCase(e.getAction()) && path.equals(e.getFile())) {
                n++;
            }
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


    static boolean isLinux() {
        String os = System.getProperty("os.name").toLowerCase();
        return !os.contains("win") && !os.contains("mac");
    }


  //Reflective accessors for the package-private INotify state
    private Object getINotify() throws Exception {
        Field fsw = Directory.class.getDeclaredField("FileSystemWatcher");
        fsw.setAccessible(true);
        Object fileSystemWatcher = fsw.get(dir);
        if (fileSystemWatcher == null) return null;

        Field iNotifyField = fileSystemWatcher.getClass().getDeclaredField("iNotify");
        iNotifyField.setAccessible(true);
        return iNotifyField.get(fileSystemWatcher);
    }


    private static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
