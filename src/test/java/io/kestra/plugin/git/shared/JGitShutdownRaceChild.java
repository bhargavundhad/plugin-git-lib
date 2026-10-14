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

/**
 * Child-JVM runner for the issue-#23 JGit shutdown-race harness.
 *
 * <p>
 * The clone runs on a worker thread driven by a {@link ProgressMonitor} that
 * suspends it at the critical phase — the checkout, once JGit holds the
 * {@code .git/index} lock, or the fetch, once a partial {@code .git} exists on
 * disk — while the main thread starts JVM shutdown. Without the guard, JGit's
 * exit cleanup removes the live index lock mid-checkout, so the resumed
 * {@code DirCache} commit fails with the production "Lock on ... index not
 * held." signature; with the guard, installed through {@code AbstractGitTask}'s
 * static initializer, that cleanup never runs and the clone completes.
 *
 * <p>
 * Two negative controls reproduce the failure without the guard: the unguarded
 * checkout (index lock removed mid-checkout) and the unguarded fetch (the
 * in-progress {@code .git} deleted by {@code CloneCommand} cleanup). The fetch
 * negative control is only asserted where the recursive delete is reliably
 * observable; on Windows open repository file handles can keep the {@code .git}
 * directory in place, so that one scenario is skipped there (see
 * {@code JGitShutdownRaceTest}) while the guarded fetch (the {@code .git} must
 * survive the drain) always runs. Every wait is bounded and observation-based;
 * a slow poll can only yield a loud false negative, never a false reproduction.
 */
public final class JGitShutdownRaceChild {

    /**
     * Usage error; no result file is written.
     */
    private static final int EXIT_USAGE = 3;

    /**
     * Race mode: the worker never signaled the critical phase within its
     * bounded wait.
     */
    private static final int EXIT_NO_SIGNAL = 6;

    private static final String GUARD_ON = "on";

    private static final String OUTCOME_COMPLETED = "COMPLETED";
    private static final String OUTCOME_CLONE_FAILED = "CLONE_FAILED";
    private static final String OUTCOME_REPRODUCED = "REPRODUCED_INDEX_UNLOCK";
    private static final String OUTCOME_REPRODUCED_FETCH = "REPRODUCED_FETCH_CLEANUP";

    /**
     * beginTask title of the checkout phase; the single English bundle value of
     * JGitText.checkingOutFiles. Used to tell the checkout phase (index lock
     * held) from the fetch phase (partial .git on disk).
     */
    private static final String CHECKOUT_TASK_TITLE = "Checking out files";

    /**
     * JGitText.lockOnNotHeld = "Lock on {0} not held." — {0} is the index file
     * path, not the .lock path.
     */
    private static final String LOCK_NOT_HELD_PREFIX = "Lock on ";
    private static final String LOCK_NOT_HELD_SUFFIX = "index not held";

    private static final long SIGNAL_TIMEOUT_SECONDS = 30;
    // Bounded window, on the clone thread, for shutdown cleanup to disrupt the in-flight operation after the
    // main thread has started shutdown: 3s is far longer than JGit's hook needs while still being observed
    // (poll the lock / .git state) rather than slept, so the guarded runs resume and complete deterministically.
    private static final long CRITICAL_PHASE_TIMEOUT_SECONDS = 3;
    private static final long POLL_MILLIS = 5;
    private static final long DRAIN_TIMEOUT_SECONDS = 60;

    private JGitShutdownRaceChild() {
    }

    public static void main(String[] args) throws InterruptedException {
        Map<String, String> options;
        String trigger;
        try {
            options = parseArgs(args);
            trigger = options.get("--trigger");
        } catch (IllegalArgumentException e) {
            System.err.println("usage: JGitShutdownRaceChild --source <uri> --target <dir> --result <file> "
                    + "--trigger checkout|fetch [--guard on|off]");
            System.err.println(e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }

        if (GUARD_ON.equals(options.get("--guard"))) {
            installGuardThroughProductionInitializer();
        }

        runShutdownRace(trigger, options);
    }

    private static void runShutdownRace(String trigger, Map<String, String> options) throws InterruptedException {
        Path resultFile = Path.of(options.get("--result"));
        Path target = Path.of(options.get("--target"));
        CountDownLatch workerSuspended = new CountDownLatch(1);
        AtomicBoolean outcomeWritten = new AtomicBoolean(false);
        AtomicBoolean outcomeRecorded = new AtomicBoolean(false);

        // Keeps the JVM alive during shutdown so the suspended worker can resume and record its outcome —
        // mirroring a worker's graceful drain. Runs concurrently with JGit's own cleanup hook and never blocks it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);
                while (!outcomeRecorded.get() && System.nanoTime() < deadline) {
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-race-drain-hook"));

        RaceMonitor monitor = new RaceMonitor(trigger,
                target.resolve(".git").resolve("index.lock"),
                target.resolve(".git"),
                workerSuspended, outcomeRecorded);
        Thread worker = new Thread(() -> runRaceClone(options, monitor, resultFile, outcomeWritten, outcomeRecorded),
                "shutdown-race-clone");
        worker.start();

        if (!workerSuspended.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("outcome", OUTCOME_CLONE_FAILED);
            result.put("detail", "worker never signaled the '" + trigger + "' phase within "
                    + SIGNAL_TIMEOUT_SECONDS + "s");
            writeResultOnce(resultFile, result, outcomeWritten, outcomeRecorded);
            System.exit(EXIT_NO_SIGNAL);
            return;
        }

        // The worker has confirmed the critical on-disk state (index lock / .git) before counting down, so
        // initiating shutdown here races JGit's exit cleanup against a genuinely in-flight operation.
        System.exit(0);
    }

    private static void runRaceClone(Map<String, String> options, RaceMonitor monitor, Path resultFile,
            AtomicBoolean outcomeWritten, AtomicBoolean outcomeRecorded) {
        boolean checkoutMode = "checkout".equals(options.get("--trigger"));
        Map<String, String> result = new LinkedHashMap<>();
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
        }
        // The single on-disk observation the harness makes: did shutdown cleanup remove the state the worker
        // was suspended on? The recorded field is scenario-specific, reusing the same criticalStateReleased
        // observation — the index lock for checkout, the partial .git for fetch.
        boolean criticalStateReleased = monitor.criticalStateReleased;
        if (checkoutMode) {
            result.put("lockReleased", Boolean.toString(criticalStateReleased));
        } else {
            result.put("gitDirSurvived", Boolean.toString(!criticalStateReleased));
            // Fetch-cleanup reproduction is the disappearance of the in-progress .git during shutdown — its
            // incoming object store / pack index vanish with it — observed directly, independent of which
            // exception the resumed fetch then surfaced. Only meaningful when the clone genuinely failed.
            if (criticalStateReleased && !OUTCOME_COMPLETED.equals(result.get("outcome"))) {
                result.put("outcome", OUTCOME_REPRODUCED_FETCH);
            }
        }
        writeResultOnce(resultFile, result, outcomeWritten, outcomeRecorded);
    }

    /**
     * Installs the issue-#23 guard through the production initialization point
     * — {@code AbstractGitTask}'s static initializer — so the harness validates
     * the real wiring, not just the isolation mechanism.
     */
    private static void installGuardThroughProductionInitializer() {
        try {
            Class.forName("io.kestra.plugin.git.shared.AbstractGitTask");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("guard installation point class not found", e);
        }
    }

    private static void clone(Map<String, String> options, ProgressMonitor monitor) throws Exception {
        Path target = Path.of(options.get("--target"));
        Files.createDirectories(target); // mirrors CloneService.cloneRepository
        CloneCommand command = Git.cloneRepository()
                .setURI(options.get("--source"))
                .setDirectory(target.toFile())
                .setProgressMonitor(monitor);
        try (Git ignored = command.call()) {
            // Closed immediately: the harness measures the clone, not the opened repository.
        }
    }

    /**
     * Writes the result file at most once, then releases the drain latch. A
     * failing write is never silent: it prints the stack trace so the
     * redirected child log carries why the parent will find no result file.
     */
    private static void writeResultOnce(
            Path resultFile,
            Map<String, String> result,
            AtomicBoolean written,
            AtomicBoolean outcomeRecorded
    ) {
        if (written.compareAndSet(false, true)) {
            try {
                StringBuilder lines = new StringBuilder();
                result.forEach((key, value)
                        -> lines.append(key).append('=').append(value).append('\n'));
                Files.writeString(resultFile, lines.toString(), StandardCharsets.UTF_8);
                outcomeRecorded.set(true);
            } catch (IOException e) {
                e.printStackTrace(System.out);
            }
        }
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
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
        if (!"checkout".equals(trigger) && !"fetch".equals(trigger)) {
            throw new IllegalArgumentException("--trigger must be checkout or fetch");
        }
        String guard = options.getOrDefault("--guard", "off");
        if (!"on".equals(guard) && !"off".equals(guard)) {
            throw new IllegalArgumentException("--guard must be on or off");
        }
        return options;
    }

    /**
     * Suspends the clone at the critical phase and waits, on the clone thread,
     * until shutdown cleanup has had a bounded window to disrupt it. In
     * {@code checkout} mode the suspension point is the first "Checking out
     * files" progress update (JGit holds the {@code .git/index} lock from then
     * on); in {@code fetch} mode it is the first fetch-phase task (a partial
     * {@code .git} directory is on disk and {@code CloneCommand}'s cleanup
     * listener is registered). Before signalling the main thread to shut down,
     * the worker confirms the relevant on-disk state exists, so a reproduction
     * can only mean cleanup removed something that genuinely existed.
     */
    private static final class RaceMonitor implements ProgressMonitor {

        private final boolean checkoutMode;
        private final Path indexLock;
        private final Path gitDir;
        private final CountDownLatch workerSuspended;
        private final AtomicBoolean outcomeRecorded;
        private boolean entryAttempted;
        private boolean inCheckoutTask;
        private boolean criticalStateReleased;

        private RaceMonitor(String trigger, Path indexLock, Path gitDir, CountDownLatch workerSuspended,
                AtomicBoolean outcomeRecorded) {
            this.checkoutMode = "checkout".equals(trigger);
            this.indexLock = indexLock;
            this.gitDir = gitDir;
            this.workerSuspended = workerSuspended;
            this.outcomeRecorded = outcomeRecorded;
        }

        @Override
        public void beginTask(String title, int totalWork) {
            if (checkoutMode) {
                // Checkout mode suspends on the checkout phase's first progress unit, not its beginTask.
                inCheckoutTask = CHECKOUT_TASK_TITLE.equals(title);
            } else if (!CHECKOUT_TASK_TITLE.equals(title)) {
                // Fetch mode: the fetch phase always precedes checkout, so the first non-checkout
                // beginTask runs inside fetch() with CloneCommand's cleanup listener registered.
                enterCriticalSection();
            }
        }

        @Override
        public void update(int completed) {
            if (checkoutMode && inCheckoutTask) {
                enterCriticalSection();
            }
        }

        private void enterCriticalSection() {
            if (entryAttempted || outcomeRecorded.get()) {
                return;
            }
            entryAttempted = true;

            if (!pollUntilCriticalStatePresent()) {
                return; // Never reached the verified critical phase.
            }

            workerSuspended.countDown();
            pollUntilCriticalStateReleased();
        }

        private boolean pollUntilCriticalStatePresent() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SIGNAL_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (Files.exists(criticalPath())) {
                    return true;
                }
                if (!sleep()) {
                    return false;
                }
            }
            return false;
        }

        private void pollUntilCriticalStateReleased() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CRITICAL_PHASE_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (!Files.exists(criticalPath())) {
                    criticalStateReleased = true;
                    return;
                }
                if (!sleep()) {
                    return;
                }
            }
            // Budget exhausted with the state still present: the guard suppressed cleanup (guarded run),
            // or cleanup was racing slowly. Either way the clone resumes and its real outcome is recorded.
        }

        private Path criticalPath() {
            return checkoutMode ? indexLock : gitDir;
        }

        private boolean sleep() {
            try {
                Thread.sleep(POLL_MILLIS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void start(int totalTasks) {
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
    }
}
