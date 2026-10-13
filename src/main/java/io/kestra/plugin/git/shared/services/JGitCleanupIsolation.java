package io.kestra.plugin.git.shared.services;

import org.eclipse.jgit.internal.util.CleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops JGit from running its JVM-exit cleanup while a Kestra task is still
 * draining — the root cause of issue #23 ("Lock on .git/index not held" on
 * graceful worker shutdown).
 *
 * <p>
 * <strong>Mechanism (pinned JGit 7.8.0.202609011348-r).</strong> Outside OSGi,
 * JGit's {@code ShutdownHook} installs a single {@link Runtime} shutdown hook
 * the first time it is used, and that hook runs every registered listener at
 * exit — among them {@code LockFile::unlock} and {@code CloneCommand}'s
 * partial-clone cleanup. A Git task still in flight when the worker drains
 * therefore loses its live {@code .git/index} lock (or its half-fetched
 * directory) mid-operation. Constructing {@code CleanupService} through its
 * public OSGi component constructor flips the process-wide singleton into OSGi
 * mode, where registration only stores the runnable and the {@code Runtime}
 * hook is never installed. There is no supported public opt-out (upstream
 * eclipse-jgit/jgit#36), and this relies on an internal JGit API with no
 * compatibility contract — see the maintenance note beside the JGit dependency
 * in {@code build.gradle}.
 *
 * <p>
 * <strong>Scope and ordering.</strong> The switch is JVM-global for every JGit
 * consumer sharing this JGit copy's classloader, and it suppresses <em>all</em>
 * JGit exit cleanup, not only the index lock. It must run before anything else
 * initializes {@code ShutdownHook};
 * {@link io.kestra.plugin.git.shared.AbstractGitTask}'s static initializer is
 * the earliest point shared by every task, but if another consumer reaches JGit
 * first the already-installed hook cannot be taken back and the protection is
 * simply inactive — never worse than unprotected. {@code JGitShutdownRaceTest}
 * is the behavioral canary for both the mechanism and the wiring.
 *
 * <p>
 * <strong>Remaining limitation.</strong> Because exit cleanup is suppressed
 * process-wide, a JVM that dies abnormally (SIGKILL, OOM) or an operation
 * interrupted mid-flight can leave behind what JGit would otherwise have
 * cleaned up at exit: {@code index.lock}, {@code HEAD.lock},
 * {@code config.lock}, {@code packed-refs.lock}, {@code refs/**}{@code /.lock},
 * {@code shallow.lock} and a partial-clone directory. This library performs no
 * automatic deletion of those — a lock file's existence or age does not prove
 * it has no live owner, and deleting one held by a concurrent process corrupts
 * that process's repository. Clearing them is left to the operator or to the
 * next run against a private, per-execution working directory. During a
 * graceful shutdown this is not a regression: in-flight operations complete and
 * release their own locks as they always did.
 *
 * <p>
 * <strong>Failure policy.</strong> {@link #isolate()} catches only
 * {@link LinkageError} and {@link RuntimeException}, records
 * {@link State#FAILED} and logs one warning; fatal JVM errors propagate.
 * {@link #isolateSafely()} additionally never throws, so it is safe from a
 * class initializer where an escaping error would permanently break the
 * initializing class.
 */
public final class JGitCleanupIsolation {

    private static final Logger LOG = LoggerFactory.getLogger(JGitCleanupIsolation.class);

    /**
     * Emitted by both {@link #isolate()} and {@link #isolateSafely()} when the
     * switch is unavailable; a single constant keeps the two failure paths from
     * drifting and makes the "exactly one warning" contract explicit.
     */
    private static final String INACTIVE_WARNING
            = "JGit exit-cleanup isolation unavailable ({}); the issue-#23 shutdown-race protection is INACTIVE";

    /**
     * Lifecycle of the isolation attempt, observable by tests to distinguish an
     * active guard from a dead one.
     */
    public enum State {
        /**
         * {@link #isolate()} has not been called yet.
         */
        NOT_ATTEMPTED,
        /**
         * The {@code CleanupService} switch succeeded; effective only if JGit's
         * shutdown hook was not already installed.
         */
        ACTIVE,
        /**
         * The switch failed (incompatible JGit, security denial); behavior is
         * the unprotected default.
         */
        FAILED
    }

    private static volatile State state = State.NOT_ATTEMPTED;
    private static volatile String failureDetail;

    private JGitCleanupIsolation() {
    }

    /**
     * Installs the isolation once; later calls are no-ops. Effectiveness
     * additionally requires that no other code initialized JGit's shutdown hook
     * earlier in this JVM/classloader.
     */
    public static void isolate() {
        if (state != State.NOT_ATTEMPTED) {
            return;
        }
        synchronized (JGitCleanupIsolation.class) {
            if (state != State.NOT_ATTEMPTED) {
                return;
            }
            try {
                // Public by OSGi-DS requirement, internal by contract; documented as "do not call explicitly".
                new CleanupService();
                state = State.ACTIVE;
                LOG.info("JGit exit cleanup is isolated from this JVM (issue #23 shutdown-race protection active)");
            } catch (LinkageError | RuntimeException e) {
                recordFailure(e);
            }
        }
    }

    /**
     * {@link #isolate()} for use from class initializers: catches ordinary
     * exceptions and linkage errors to prevent class-initialization failure,
     * but rethrows fatal JVM errors ({@code VirtualMachineError} and its
     * subclasses such as {@code OutOfMemoryError} and
     * {@code StackOverflowError}). Non-fatal errors are logged as warnings
     * rather than failing the class, keeping the isolation attempt loud without
     * silently breaking the task base.
     */
    public static void isolateSafely() {
        try {
            isolate();
        } catch (VirtualMachineError e) {
            // Fatal JVM errors must propagate; they already indicate a terminal JVM condition.
            throw e;
        } catch (LinkageError | Exception e) {
            // isolate() already records FAILED and logs the warning for anything it catches, so this only fires
            // when something escaped before the state was set; never emit a second warning for the same failure.
            if (state == State.NOT_ATTEMPTED) {
                recordFailure(e);
            }
        }
    }

    /**
     * Records a failed isolation attempt once: stores the detail, flips to
     * {@link State#FAILED} and logs the warning.
     */
    private static void recordFailure(Throwable e) {
        state = State.FAILED;
        failureDetail = e.getClass().getName() + ": " + e.getMessage();
        LOG.warn(INACTIVE_WARNING, failureDetail, e);
    }

    public static State state() {
        return state;
    }

    public static String failureDetail() {
        return failureDetail;
    }
}
