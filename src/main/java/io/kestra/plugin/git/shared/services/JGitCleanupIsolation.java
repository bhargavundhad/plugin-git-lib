package io.kestra.plugin.git.shared.services;

import org.eclipse.jgit.internal.util.CleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suppresses JGit's JVM-exit cleanup (issue #23: "Lock on .git/index not held"
 * when a worker drains during a task) by constructing the internal
 * {@code CleanupService} in OSGi mode, which prevents this JGit copy from
 * registering its runtime shutdown hook when isolation is applied before that
 * hook has already been installed. The effect is classloader-wide (every JGit
 * consumer sharing this JGit copy's classloader), not JVM-global, and applies
 * only if the hook has not already been installed by an earlier JGit operation
 * — otherwise it is simply inactive, never worse than unprotected.
 */
public final class JGitCleanupIsolation {

    private static final Logger LOG = LoggerFactory.getLogger(JGitCleanupIsolation.class);

    private static volatile boolean attempted = false;

    private JGitCleanupIsolation() {
    }

    /**
     * Attempts the isolation once; later calls are no-ops. Fail-safe: a missing or
     * incompatible internal JGit API logs one warning and leaves the unprotected
     * default rather than throwing out of a class initializer (which would break
     * every Git task through {@code ExceptionInInitializerError}); only fatal JVM
     * errors propagate.
     */
    public static void isolateSafely() {
        if (attempted) {
            return;
        }
        synchronized (JGitCleanupIsolation.class) {
            if (attempted) {
                return;
            }
            attempted = true;
            try {
                // Public by OSGi-DS requirement, internal by contract ("do not call explicitly"):
                // constructing it flips the singleton into OSGi mode so this JGit copy registers no
                // runtime shutdown hook, provided one was not already installed.
                new CleanupService();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                LOG.warn("JGit exit-cleanup isolation unavailable ({}); the issue-#23 shutdown-race protection is INACTIVE",
                        t.getClass().getName() + ": " + t.getMessage(), t);
            }
        }
    }
}
