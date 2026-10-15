package io.kestra.plugin.git.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * Child JVM for {@link JGitShutdownRaceTest}: suspends a clone at its critical phase, then starts JVM shutdown.
 * Args: source-uri target-dir result-file checkout|fetch on|off.
 */
public final class JGitShutdownRaceChild {

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[1]);
        Path result = Path.of(args[2]);
        boolean checkout = "checkout".equals(args[3]);
        if ("on".equals(args[4])) {
            Class.forName("io.kestra.plugin.git.shared.AbstractGitTask"); // installs the guard, like production
        }

        CountDownLatch suspended = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        Path critical = checkout ? target.resolve(".git/index.lock") : target.resolve(".git");
        Monitor monitor = new Monitor(checkout, critical, suspended);

        // Keeps the JVM alive during shutdown until the suspended clone has recorded its outcome.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!written.get() && System.nanoTime() < deadline) {
                sleep();
            }
        }));

        new Thread(() -> {
            Map<String, String> out = new LinkedHashMap<>();
            try {
                Files.createDirectories(target);
                Git.cloneRepository().setURI(args[0]).setDirectory(target.toFile())
                    .setProgressMonitor(monitor).call().close();
                out.put("outcome", "COMPLETED");
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                String message = String.valueOf(t).replaceAll("\\s+", " ");
                boolean indexUnlock = message.contains("Lock on ") && message.contains("index not held");
                out.put("outcome", checkout && indexUnlock ? "REPRODUCED_INDEX_UNLOCK"
                    : !checkout && monitor.released ? "REPRODUCED_FETCH_CLEANUP" : "CLONE_FAILED");
                out.put("message", message);
            }
            out.put("stateSurvived", String.valueOf(!monitor.released));
            try {
                StringBuilder lines = new StringBuilder();
                out.forEach((k, v) -> lines.append(k).append('=').append(v).append('\n'));
                Files.writeString(result, lines.toString());
            } catch (Exception e) {
                e.printStackTrace(System.out);
            } finally {
                written.set(true);
            }
        }).start();

        if (!suspended.await(30, TimeUnit.SECONDS)) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checkout: suspends on the first "Checking out files" update (index lock held). Fetch: suspends on the first
     * fetch-phase task (partial .git on disk). Signals main only once the on-disk state is confirmed to exist.
     */
    private static final class Monitor implements ProgressMonitor {
        private final boolean checkout;
        private final Path critical;
        private final CountDownLatch suspended;
        private boolean inCheckoutTask;
        private boolean entered;
        volatile boolean released;

        Monitor(boolean checkout, Path critical, CountDownLatch suspended) {
            this.checkout = checkout;
            this.critical = critical;
            this.suspended = suspended;
        }

        @Override
        public void beginTask(String title, int totalWork) {
            inCheckoutTask = "Checking out files".equals(title);
            if (!checkout && !inCheckoutTask) { // fetch always precedes checkout
                enter();
            }
        }

        @Override
        public void update(int completed) {
            if (checkout && inCheckoutTask) {
                enter();
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

        private void enter() {
            if (entered) {
                return;
            }
            entered = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(critical)) {
                if (System.nanoTime() > deadline) {
                    return;
                }
                sleep();
            }
            suspended.countDown();
            // 3s window for shutdown cleanup to remove the state; guarded runs just time out and resume.
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && !released) {
                released = !Files.exists(critical);
                sleep();
            }
        }
    }
}
