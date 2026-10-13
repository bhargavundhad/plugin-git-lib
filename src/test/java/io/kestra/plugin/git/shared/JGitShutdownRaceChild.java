package io.kestra.plugin.git.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;

import io.kestra.plugin.git.shared.services.JGitCleanupIsolation;

/**
 * Child-JVM runner for the JGit shutdown-race regression harness (issue #23).
 *
 * <p>
 * With no trigger (or {@code --trigger none}) it performs a plain clone of a
 * local repository into the given target directory and records the outcome in a
 * key=value result file — the control mode.
 *
 * <p>
 * With {@code --trigger checkout} it reproduces the issue-#23 failure
 * deterministically: the clone runs on a worker thread whose progress monitor,
 * inside the first "Checking out files" update — JGit holds the
 * {@code .git/index} lock at that point, verified against the pinned JGit
 * sources — confirms the index lock exists on disk, then signals the main
 * thread and suspends the checkout by polling until the on-disk
 * {@code index.lock} disappears. The main thread waits for lock confirmation
 * before starting JVM shutdown; JGit's shutdown hook then unlocks the live
 * index lock (clearing {@code LockFile.haveLck} and deleting
 * {@code index.lock}) while the checkout thread is suspended. When the lock
 * file is observed gone, the checkout resumes and its next
 * {@code DirCache.commit} must fail in {@code LockFile.requireLock} with "Lock
 * on &lt;index&gt; not held." — the production signature. A dedicated drain
 * hook keeps the JVM alive (bounded) so the clone thread can record the outcome
 * before halt; it runs concurrently with JGit's own hook and never blocks it.
 *
 * <p>
 * With {@code --trigger fetch} it exercises issue #23's second production
 * failure mode: the clone runs on a worker thread whose progress monitor
 * suspends it inside the first fetch-phase {@code beginTask} — after {@code
 * CloneCommand.call()} has registered its {@code this::cleanup} shutdown
 * listener but before fetch finishes, with the partially created {@code .git}
 * directory on disk. Once that directory is confirmed, the main thread starts
 * JVM shutdown; guarded, the cleanup listener is suppressed, the directory
 * survives, the fetch resumes and the clone completes (asserted by
 * {@code JGitShutdownRaceTest}). The result also records whether the
 * {@code .git} directory vanished ({@code fetchCleanupObserved}), but the
 * unguarded deletion is deliberately not asserted: {@code
 * CloneCommand.cleanup()} is a best-effort recursive remove that ignores
 * failures, so a directory whose repository handles are still open — notably on
 * Windows — is not reliably force-removed from a forked JVM at this suspension
 * point. The signal is title-agnostic — it fires on the first {@code beginTask}
 * that is not the checkout phase — so a clone that never reports a fetch-phase
 * task can only yield a loud false negative, never a false reproduction.
 *
 * <p>
 * Every wait is bounded; the only timing-dependent steps are the polls
 * observing the lock existence and deletion within their budgets. A miss in
 * either poll can only turn the race into a loud false negative (outcome
 * COMPLETED or LOCK_CONFIRMATION_TIMEOUT instead of the reproduction), never a
 * false positive.
 */
public final class JGitShutdownRaceChild {

    /**
     * Usage error; no result file is written.
     */
    private static final int EXIT_USAGE = 3;

    /**
     * Race mode: the checkout-start signal never arrived within its bounded
     * wait.
     */
    private static final int EXIT_TRIGGER_TIMEOUT = 6;

    /**
     * Race mode: the index lock was never observed before the bounded
     * confirmation timeout.
     */
    private static final int EXIT_LOCK_CONFIRMATION_TIMEOUT = 7;

    private static final String TRIGGER_NONE = "none";
    private static final String TRIGGER_CHECKOUT = "checkout";
    private static final String TRIGGER_FETCH = "fetch";

    private static final String GUARD_ON = "on";
    private static final String GUARD_OFF = "off";

    private static final String OUTCOME_COMPLETED = "COMPLETED";
    private static final String OUTCOME_CLONE_FAILED = "CLONE_FAILED";
    private static final String OUTCOME_REPRODUCED = "REPRODUCED_INDEX_UNLOCK";
    private static final String OUTCOME_REPRODUCED_FETCH = "REPRODUCED_FETCH_CLEANUP";
    private static final String OUTCOME_TRIGGER_TIMEOUT = "TRIGGER_TIMEOUT";
    private static final String OUTCOME_LOCK_CONFIRMATION_TIMEOUT = "LOCK_CONFIRMATION_TIMEOUT";

    /**
     * beginTask title of the checkout phase; single English bundle value of
     * JGitText.checkingOutFiles.
     */
    private static final String CHECKOUT_TASK_TITLE = "Checking out files";

    /**
     * JGitText.lockOnNotHeld = "Lock on {0} not held." — {0} is the index file
     * path, not the .lock path.
     */
    private static final String LOCK_NOT_HELD_PREFIX = "Lock on ";
    private static final String LOCK_NOT_HELD_SUFFIX = "index not held";

    private static final long STARTED_TIMEOUT_SECONDS = 30;
    private static final long LOCK_CONFIRMATION_TIMEOUT_SECONDS = 5;
    private static final long LOCK_RELEASE_TIMEOUT_SECONDS = 10;
    private static final long LOCK_RELEASE_POLL_MILLIS = 5;
    private static final long DRAIN_TIMEOUT_SECONDS = 60;

    private JGitShutdownRaceChild() {
    }

    public static void main(String[] args) throws InterruptedException {
        Map<String, String> options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(
                    "usage: JGitShutdownRaceChild --source <uri> --target <dir> --result <file> [--trigger none|checkout|fetch] [--guard on|off]");
            System.err.println(e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }

        if (GUARD_ON.equals(options.get("--guard"))) {
            installGuardThroughProductionInitializer();
        }

        if (TRIGGER_CHECKOUT.equals(options.get("--trigger"))) {
            runShutdownRace(options);
            return;
        }

        if (TRIGGER_FETCH.equals(options.get("--trigger"))) {
            runFetchRace(options);
            return;
        }

        Map<String, String> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            clone(options, null);
            result.put("outcome", OUTCOME_COMPLETED);
        } catch (Throwable t) {
            t.printStackTrace(System.out); // lands in the redirected child-output log
            result.put("outcome", OUTCOME_CLONE_FAILED);
            result.put("exceptionClass", t.getClass().getName());
            result.put("message", singleLine(t.toString()));
        }
        result.put("cloneMillis", Long.toString((System.nanoTime() - start) / 1_000_000));
        writeOutcomeOnce(resultFileOf(options), result, new AtomicBoolean(), null, false);
    }

    private static void runShutdownRace(Map<String, String> options) throws InterruptedException {
        Path resultFile = resultFileOf(options);
        CountDownLatch checkoutStarted = new CountDownLatch(1);
        CountDownLatch outcomeRecorded = new CountDownLatch(1);
        AtomicBoolean outcomeWritten = new AtomicBoolean(false);

        // Keeps the JVM alive while shutdown hooks run so the clone thread can record the outcome — mirroring the
        // worker's graceful drain. It runs concurrently with JGit's cleanup hook and never blocks it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                outcomeRecorded.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-race-drain-hook"));

        ShutdownRaceMonitor monitor = new ShutdownRaceMonitor(
                Path.of(options.get("--target"), ".git", "index.lock"), checkoutStarted);
        Thread cloneThread = new Thread(
                () -> runRaceClone(options, monitor, resultFile, outcomeWritten, outcomeRecorded),
                "shutdown-race-clone");
        cloneThread.start();

        if (!checkoutStarted.await(STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("outcome", OUTCOME_TRIGGER_TIMEOUT);
            result.put("detail",
                    "no update(1) within " + STARTED_TIMEOUT_SECONDS + "s of beginTask(\"" + CHECKOUT_TASK_TITLE + "\")");
            writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, false);
            System.exit(EXIT_TRIGGER_TIMEOUT);
            return;
        }

        // Wait for the monitor to confirm the index lock exists before initiating shutdown.
        // This ensures we only race when we've verified we're actually in the checkout phase holding the lock.
        if (!monitor.lockConfirmed()) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("outcome", OUTCOME_LOCK_CONFIRMATION_TIMEOUT);
            result.put("detail",
                    "index lock not observed within " + LOCK_CONFIRMATION_TIMEOUT_SECONDS + "s after checkout started");
            writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, false);
            System.exit(EXIT_LOCK_CONFIRMATION_TIMEOUT);
            return;
        }

        // Initiating the shutdown here is the reproduction: JGit's hook unlocks the live index lock while the
        // checkout is suspended in awaitIndexLockRelease(). The JVM halts once the drain hook has seen the
        // recorded outcome, so exit code 0 always accompanies a written result file.
        System.exit(0);
    }

    private static void runFetchRace(Map<String, String> options) throws InterruptedException {
        Path resultFile = resultFileOf(options);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch outcomeRecorded = new CountDownLatch(1);
        AtomicBoolean outcomeWritten = new AtomicBoolean(false);

        // Same drain hook as the checkout race: it keeps the JVM alive so the suspended fetch thread can resume and
        // record its outcome before halt, and runs concurrently with JGit's cleanup hook without blocking it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                outcomeRecorded.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-race-drain-hook"));

        FetchRaceMonitor monitor = new FetchRaceMonitor(
                Path.of(options.get("--target"), ".git"), fetchStarted);
        Thread cloneThread = new Thread(
                () -> runFetchClone(options, monitor, resultFile, outcomeWritten, outcomeRecorded),
                "shutdown-race-clone");
        cloneThread.start();

        if (!fetchStarted.await(STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("outcome", OUTCOME_TRIGGER_TIMEOUT);
            result.put("detail", "no fetch-phase beginTask within " + STARTED_TIMEOUT_SECONDS + "s");
            writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, false);
            System.exit(EXIT_TRIGGER_TIMEOUT);
            return;
        }

        // Confirm the partial .git directory is on disk before shutdown, so a reproduction can only mean cleanup
        // deleted a directory that genuinely existed (never a directory that was simply never created).
        if (!monitor.dirConfirmed()) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("outcome", OUTCOME_LOCK_CONFIRMATION_TIMEOUT);
            result.put("detail", ".git not observed within " + LOCK_CONFIRMATION_TIMEOUT_SECONDS
                    + "s after fetch began");
            writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, false);
            System.exit(EXIT_LOCK_CONFIRMATION_TIMEOUT);
            return;
        }

        System.exit(0);
    }

    private static void runFetchClone(Map<String, String> options, FetchRaceMonitor monitor, Path resultFile,
            AtomicBoolean outcomeWritten, CountDownLatch outcomeRecorded) {
        Map<String, String> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            clone(options, monitor);
            result.put("outcome", OUTCOME_COMPLETED);
        } catch (Throwable t) {
            t.printStackTrace(System.out); // lands in the redirected child-output log
            result.put("exceptionClass", t.getClass().getName());
            result.put("message", singleLine(t.toString()));
            // Mechanism fingerprint: JGit's shutdown cleanup deleted the in-progress .git while the fetch was
            // suspended, so the directory vanishing is the reproduction regardless of how the fetch then failed.
            result.put("outcome", monitor.cleanupObserved() ? OUTCOME_REPRODUCED_FETCH : OUTCOME_CLONE_FAILED);
        }
        result.put("fetchTriggered", Boolean.toString(monitor.signaled()));
        result.put("gitDirConfirmed", Boolean.toString(monitor.dirConfirmed()));
        result.put("gitDirExistedBeforeWait", Boolean.toString(monitor.dirExistedBeforeWait()));
        result.put("fetchCleanupObserved", Boolean.toString(monitor.cleanupObserved()));
        result.put("gitDirStillPresentAfterWait", Boolean.toString(monitor.dirStillPresentAfterWait()));
        result.put("guardState", JGitCleanupIsolation.state().name());
        if (JGitCleanupIsolation.failureDetail() != null) {
            result.put("guardFailureDetail", singleLine(JGitCleanupIsolation.failureDetail()));
        }
        result.put("cloneMillis", Long.toString((System.nanoTime() - start) / 1_000_000));
        writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, true);
    }

    /**
     * Installs the issue-#23 guard through the production initialization point
     * — AbstractGitTask's class initializer — so the harness validates the
     * production wiring, not just the isolation mechanism.
     */
    private static void installGuardThroughProductionInitializer() {
        try {
            Class.forName("io.kestra.plugin.git.shared.AbstractGitTask");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("guard installation point class not found", e);
        }
    }

    private static void runRaceClone(Map<String, String> options, ShutdownRaceMonitor monitor, Path resultFile,
            AtomicBoolean outcomeWritten, CountDownLatch outcomeRecorded) {
        Map<String, String> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            clone(options, monitor);
            result.put("outcome", OUTCOME_COMPLETED);
        } catch (Throwable t) {
            t.printStackTrace(System.out); // lands in the redirected child-output log
            String message = singleLine(t.toString());
            boolean reproduced = t instanceof IllegalStateException
                    && message.contains(LOCK_NOT_HELD_PREFIX)
                    && message.contains(LOCK_NOT_HELD_SUFFIX);
            result.put("outcome", reproduced ? OUTCOME_REPRODUCED : OUTCOME_CLONE_FAILED);
            result.put("exceptionClass", t.getClass().getName());
            result.put("message", message);
            if (reproduced) {
                // Mechanism fingerprint: the shutdown hook deleted the lock file out from under the checkout.
                result.put("indexLockExistsAtFailure",
                        Boolean.toString(Files.exists(Path.of(options.get("--target"), ".git", "index.lock"))));
            }
        }
        result.put("checkoutTriggered", Boolean.toString(monitor.signaled()));
        result.put("lockConfirmed", Boolean.toString(monitor.lockConfirmed()));
        result.put("lockReleasedObserved", Boolean.toString(monitor.lockReleasedObserved()));
        result.put("lockExistedBeforeWait", Boolean.toString(monitor.lockExistedBeforeWait()));
        result.put("lockStillPresentAfterWait", Boolean.toString(monitor.lockStillPresentAfterWait()));
        result.put("guardState", JGitCleanupIsolation.state().name());
        if (JGitCleanupIsolation.failureDetail() != null) {
            result.put("guardFailureDetail", singleLine(JGitCleanupIsolation.failureDetail()));
        }
        result.put("cloneMillis", Long.toString((System.nanoTime() - start) / 1_000_000));
        writeOutcomeOnce(resultFile, result, outcomeWritten, outcomeRecorded, true);
    }

    private static void clone(Map<String, String> options, ProgressMonitor monitor) throws Exception {
        Path target = Path.of(options.get("--target"));
        Files.createDirectories(target); // mirrors CloneService.cloneRepository
        CloneCommand command = Git.cloneRepository()
                .setURI(options.get("--source"))
                .setDirectory(target.toFile());
        if (monitor != null) {
            command.setProgressMonitor(monitor);
        }
        try (Git ignored = command.call()) {
            // Closed immediately: the harness measures the clone, not the opened repository.
        }
    }

    /**
     * Writes the result file at most once; later callers only release the
     * latch. A failing write must not be silent: outside shutdown it exits
     * non-zero, and during shutdown it prints the stack trace so the redirected
     * child log carries the reason the parent will find no result file.
     */
    private static void writeOutcomeOnce(Path resultFile, Map<String, String> result, AtomicBoolean written,
            CountDownLatch outcomeRecorded, boolean duringShutdown) {
        if (!written.compareAndSet(false, true)) {
            if (outcomeRecorded != null) {
                outcomeRecorded.countDown();
            }
            return;
        }
        try {
            writeResult(resultFile, result);
        } catch (IOException e) {
            e.printStackTrace(System.out);
            if (!duringShutdown) {
                System.exit(1);
            }
        } finally {
            if (outcomeRecorded != null) {
                outcomeRecorded.countDown();
            }
        }
    }

    private static void writeResult(Path resultFile, Map<String, String> result) throws IOException {
        StringBuilder lines = new StringBuilder();
        result.forEach((key, value) -> lines.append(key).append('=').append(value).append('\n'));
        Files.writeString(resultFile, lines.toString(), StandardCharsets.UTF_8);
    }

    private static Path resultFileOf(Map<String, String> options) {
        return Path.of(options.get("--result"));
    }

    private static String singleLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> parseArgs(String[] args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("expected --flag value pairs, got " + args.length + " tokens");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String flag = args[i];
            if (!flag.startsWith("--") || options.containsKey(flag)) {
                throw new IllegalArgumentException("unexpected or duplicate flag: " + flag);
            }
            options.put(flag, args[i + 1]);
        }
        if (!options.containsKey("--source") || !options.containsKey("--target") || !options.containsKey("--result")) {
            throw new IllegalArgumentException("--source, --target and --result are required");
        }
        String trigger = options.get("--trigger");
        if (trigger != null && !TRIGGER_NONE.equals(trigger) && !TRIGGER_CHECKOUT.equals(trigger)
                && !TRIGGER_FETCH.equals(trigger)) {
            throw new IllegalArgumentException(
                    "--trigger must be " + TRIGGER_NONE + ", " + TRIGGER_CHECKOUT + " or " + TRIGGER_FETCH);
        }
        String guard = options.getOrDefault("--guard", GUARD_OFF);
        if (!GUARD_ON.equals(guard) && !GUARD_OFF.equals(guard)) {
            throw new IllegalArgumentException("--guard must be " + GUARD_ON + " or " + GUARD_OFF);
        }
        return options;
    }

    /**
     * Signals the first completion unit of the checkout task and then suspends
     * the checkout thread until the shutdown hook has released the index lock.
     * JGit offers no public accessor for the {@code LockFile} held by
     * {@code CloneCommand.checkout}, so the release is observed through the
     * on-disk lock file: {@code LockFile.unlock} clears {@code haveLck} before
     * deleting the file, so a missing file proves the in-memory lock is already
     * released.
     */
    private static final class ShutdownRaceMonitor implements ProgressMonitor {

        private final Path indexLockFile;
        private final CountDownLatch checkoutStarted;
        private final CountDownLatch lockConfirmed;
        private boolean inCheckoutTask;
        private boolean signaled;
        private boolean lockExistedBeforeWait;
        private boolean lockReleasedObserved;
        private boolean lockStillPresentAfterWait;

        private ShutdownRaceMonitor(Path indexLockFile, CountDownLatch checkoutStarted) {
            this.indexLockFile = indexLockFile;
            this.checkoutStarted = checkoutStarted;
            this.lockConfirmed = new CountDownLatch(1);
        }

        boolean lockReleasedObserved() {
            return lockReleasedObserved;
        }

        private boolean lockExistedBeforeWait() {
            return lockExistedBeforeWait;
        }

        boolean signaled() {
            return signaled;
        }

        boolean lockStillPresentAfterWait() {
            return lockStillPresentAfterWait;
        }

        boolean lockConfirmed() {
            return lockConfirmed.getCount() == 0;
        }

        @Override
        public void start(int totalTasks) {
        }

        @Override
        public void beginTask(String title, int totalWork) {
            inCheckoutTask = CHECKOUT_TASK_TITLE.equals(title);
        }

        @Override
        public void update(int completed) {
            if (!inCheckoutTask || signaled) {
                return;
            }
            signaled = true;
            // First, confirm the index lock exists to prove we're actually in the checkout phase.
            // Only then signal the parent that shutdown may be initiated.
            confirmIndexLockExists();
            checkoutStarted.countDown();
            awaitIndexLockRelease();
        }

        @Override
        public void endTask() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
        }

        /**
         * Bounded condition polling, not a fixed wait: the loop ends as soon as
         * the lock file disappears, or when the budget is exhausted — which can
         * only turn the race into a loud false negative, because the suspended
         * checkout then resumes and may complete.
         */
        private void awaitIndexLockRelease() {
            // Confirm that the index lock exists before waiting for shutdown cleanup.
            lockExistedBeforeWait = Files.exists(indexLockFile);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_RELEASE_TIMEOUT_SECONDS);

            while (System.nanoTime() < deadline) {
                if (!Files.exists(indexLockFile)) {
                    lockReleasedObserved = true;
                    return;
                }

                try {
                    Thread.sleep(LOCK_RELEASE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            lockStillPresentAfterWait = Files.exists(indexLockFile);
        }

        /**
         * Confirms the index lock exists before signaling the parent to
         * initiate shutdown. This bounded wait ensures we only race when we've
         * verified we're actually in the checkout phase.
         */
        private void confirmIndexLockExists() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_CONFIRMATION_TIMEOUT_SECONDS);

            while (System.nanoTime() < deadline) {
                if (Files.exists(indexLockFile)) {
                    lockConfirmed.countDown();
                    return;
                }

                try {
                    Thread.sleep(LOCK_RELEASE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Signals the first fetch-phase task and then suspends the clone thread
     * until JGit's shutdown cleanup has had a bounded window to delete the
     * in-progress {@code .git} directory. {@code CloneCommand.call()} registers
     * its {@code this::cleanup} listener right after {@code init()} and before
     * {@code fetch()}, and {@code cleanup()} recursively deletes the
     * {@code .git} directory it created, so the on-disk disappearance of that
     * directory is a direct, public observation of the fetch-path shutdown
     * cleanup — no private JGit field is touched.
     */
    private static final class FetchRaceMonitor implements ProgressMonitor {

        private final Path gitDir;
        private final CountDownLatch fetchStarted;
        private final CountDownLatch dirConfirmed;
        private boolean signaled;
        private boolean dirExistedBeforeWait;
        private boolean cleanupObserved;
        private boolean dirStillPresentAfterWait;

        private FetchRaceMonitor(Path gitDir, CountDownLatch fetchStarted) {
            this.gitDir = gitDir;
            this.fetchStarted = fetchStarted;
            this.dirConfirmed = new CountDownLatch(1);
        }

        boolean signaled() {
            return signaled;
        }

        boolean dirConfirmed() {
            return dirConfirmed.getCount() == 0;
        }

        boolean cleanupObserved() {
            return cleanupObserved;
        }

        boolean dirExistedBeforeWait() {
            return dirExistedBeforeWait;
        }

        boolean dirStillPresentAfterWait() {
            return dirStillPresentAfterWait;
        }

        @Override
        public void start(int totalTasks) {
        }

        @Override
        public void beginTask(String title, int totalWork) {
            // The fetch phase always precedes checkout, so the first beginTask that is not the checkout title is
            // inside fetch() with the CloneCommand cleanup listener registered. Skipping the checkout title means
            // this monitor can never be triggered by the later checkout phase.
            if (signaled || CHECKOUT_TASK_TITLE.equals(title)) {
                return;
            }
            signaled = true;
            confirmGitDirExists();
            fetchStarted.countDown();
            awaitGitDirCleanup();
        }

        @Override
        public void update(int completed) {
        }

        @Override
        public void endTask() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
        }

        private void confirmGitDirExists() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_CONFIRMATION_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (Files.exists(gitDir)) {
                    dirConfirmed.countDown();
                    return;
                }
                try {
                    Thread.sleep(LOCK_RELEASE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /**
         * Bounded polling, not a fixed wait: returns as soon as the
         * {@code .git} directory disappears (cleanup ran) or when the budget is
         * exhausted (guarded, so cleanup never ran) — a miss can only produce a
         * loud false negative, never a false reproduction.
         */
        private void awaitGitDirCleanup() {
            dirExistedBeforeWait = Files.exists(gitDir);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_RELEASE_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (!Files.exists(gitDir)) {
                    cleanupObserved = true;
                    return;
                }
                try {
                    Thread.sleep(LOCK_RELEASE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            dirStillPresentAfterWait = Files.exists(gitDir);
        }
    }
}
