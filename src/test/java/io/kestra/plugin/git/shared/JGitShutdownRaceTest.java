package io.kestra.plugin.git.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * JGit shutdown-race regression harness (issue #23). Each case forks a fresh
 * child JVM ({@link JGitShutdownRaceChild}) that clones a local fixture and
 * starts JVM shutdown while the clone is suspended at a critical phase, so JGit
 * exit-cleanup races the in-flight operation exactly as a draining worker
 * would.
 *
 * <p>
 * Two unguarded negative controls prove the harness is real rather than
 * assumed: the unguarded checkout reproduces the original "Lock on
 * .../.git/index not held" failure, and the unguarded fetch reproduces the
 * {@code CloneCommand}-cleanup failure by observing the in-progress
 * {@code .git} (its incoming object store / pack index) removed during the
 * drain. The guarded checkout and guarded fetch then assert the clone completes
 * and the critical on-disk state survives. The guard is installed through the
 * production initialization point, {@code AbstractGitTask}'s static
 * initializer, so the harness also validates the real wiring.
 *
 * <p>
 * The unguarded fetch control is skipped on Windows only: {@code
 * CloneCommand.cleanup} is a best-effort recursive delete and open repository
 * file handles can keep the {@code .git} directory in place there, so the
 * deletion cannot be reliably observed. The guarded fetch, and both checkout
 * controls, always run. The parent enforces the child timeout and terminates a
 * timed-out child before reading any child output or result file; the harness
 * does not cover recovery after forced termination (SIGKILL).
 */
class JGitShutdownRaceTest {

    private static final int FIXTURE_FILE_COUNT = 2_000;
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(120);
    // Below this length the classpath is passed directly via -cp; longer values go through a
    // java @argfile so the Windows CreateProcess command-line budget is never exceeded.
    private static final int DIRECT_CLASSPATH_LIMIT = 20_000;
    private static final boolean IS_WINDOWS
            = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final List<Path> createdDirectories = new ArrayList<>();

    @Test
    void shutdownDuringUnguardedCheckoutReproducesOriginalFailure() throws Exception {
        Map<String, String> result = runChild("checkout", "off");

        assertThat(describe("child JVM timed out or exited non-zero", result),
                result.get("exit"), is("0"));
        assertThat(describe("clone outcome", result),
                result.get("outcome"), is("REPRODUCED_INDEX_UNLOCK"));
        assertThat(describe("original issue-#23 failure signature", result),
                result.get("message"), containsString("Lock on "));
        assertThat(describe("original issue-#23 failure signature", result),
                result.get("message"), containsString("index not held"));
    }

    @Test
    void shutdownDuringGuardedCheckoutCompletes() throws Exception {
        Map<String, String> result = runChild("checkout", "on");

        assertThat(describe("child JVM timed out or exited non-zero", result),
                result.get("exit"), is("0"));
        assertThat(describe("clone outcome", result),
                result.get("outcome"), is("COMPLETED"));
        assertThat(describe("index lock survived the shutdown drain", result),
                result.get("lockReleased"), is("false"));
    }

    @Test
    void shutdownDuringGuardedFetchCompletesAndGitDirSurvives() throws Exception {
        Map<String, String> result = runChild("fetch", "on");

        assertThat(describe("child JVM timed out or exited non-zero", result),
                result.get("exit"), is("0"));
        assertThat(describe("clone outcome", result),
                result.get("outcome"), is("COMPLETED"));
        assertThat(describe("partial .git survived the shutdown drain", result),
                result.get("gitDirSurvived"), is("true"));
    }

    @Test
    void shutdownDuringUnguardedFetchReproducesCleanupFailure() throws Exception {
        // On Windows the in-progress .git is held open by the fetching process, so CloneCommand's
        // best-effort recursive delete cannot reliably remove it; the reproduction is only assertable
        // where that delete is observable (e.g. Linux). The guarded fetch and both checkout controls still run.

        Assumptions.assumeFalse(IS_WINDOWS, "Unguarded fetch cleanup is not reliably observable on Windows.");

        Map<String, String> result = runChild("fetch", "off");

        assertThat(describe("child JVM timed out or exited non-zero", result),
                result.get("exit"), is("0"));
        assertThat(describe("fetch-cleanup reproduction outcome", result),
                result.get("outcome"), is("REPRODUCED_FETCH_CLEANUP"));
        assertThat(describe("missing .git (incoming object store / index) was observed", result),
                result.get("gitDirSurvived"), is("false"));
    }

    /**
     * Boots a fresh child JVM (isolated from this JVM's JGit state), waits for
     * it with an enforced timeout before reading anything it wrote, then
     * returns its result map annotated with the exit code and child log for
     * diagnostics.
     */
    private Map<String, String> runChild(String trigger, String guard) throws Exception {
        Path remote = createTempDirectory("jgit-shutdown-race-remote-");
        createFixture(remote);
        Path target = createTempDirectory("jgit-shutdown-race-target-");
        Path scratch = createTempDirectory("jgit-shutdown-race-scratch-");
        Path resultFile = scratch.resolve("child-result.txt");
        Path childLog = scratch.resolve("child-output.log");

        Process child = launchChild(childLog,
                "--source", remote.toUri().toString(),
                "--target", target.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString(),
                "--trigger", trigger,
                "--guard", guard);

        boolean finished = child.waitFor(CHILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            fail(describe("child JVM exceeded " + CHILD_TIMEOUT + " and was forcibly terminated",
                    readResult(resultFile), readOutput(childLog)));
        }

        Map<String, String> result = new LinkedHashMap<>(readResult(resultFile));
        result.put("exit", Integer.toString(child.exitValue()));
        result.put("childOutput", tail(readOutput(childLog)));
        return result;
    }

    /**
     * Launches the child on the current JVM's own classpath, read from
     * {@code java.class.path}. The Gradle test worker exposes the full test
     * runtime classpath there â€” including either the real directories/jars or
     * a manifest-only bootstrap jar whose {@code Class-Path} the launcher
     * follows transparently â€” and this code sits on the same classpath, so
     * the {@code JGitShutdownRaceChild} main class always resolves. The
     * classpath is passed directly via {@code -cp}; only if it would exceed the
     * Windows {@code CreateProcess} command-line budget does the test write its
     * own tiny java {@code @argfile} (a runtime helper, not build plumbing).
     * This never needs the removed Gradle argfile.
     */
    private Process launchChild(Path childLog, String... raceArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());

        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("java.class.path is unavailable in the test worker");
        }
        if (classpath.length() + 8 <= DIRECT_CLASSPATH_LIMIT) {
            command.add("-cp");
            command.add(classpath);
        } else {
            // Long classpath (notably on Windows): hand it to the launcher in a @argfile,
            // the same java feature used on every OS since JDK 9. Escape exactly once, here.
            Path argFile = Files.createTempFile("jgit-shutdown-race-cp", ".argfile");
            argFile.toFile().deleteOnExit();
            Files.writeString(argFile, "-cp \"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n",
                    StandardCharsets.UTF_8);
            command.add("@" + argFile.toAbsolutePath());
        }

        command.add(JGitShutdownRaceChild.class.getName());
        command.addAll(List.of(raceArgs));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(childLog.toFile()) // file-backed so the timeout above actually enforces
                .start();
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", IS_WINDOWS ? "java.exe" : "java").toString();
    }

    private Path createTempDirectory(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        createdDirectories.add(dir);
        return dir;
    }

    private static void createFixture(Path remote) throws Exception {
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            for (int i = 0; i < FIXTURE_FILE_COUNT; i++) {
                Files.writeString(remote.resolve("file-%04d.txt".formatted(i)), "content %d\n".formatted(i));
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").call();
        }
    }

    private static Map<String, String> readResult(Path resultFile) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.exists(resultFile)) {
            for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
                int at = line.indexOf('=');
                if (at > 0) {
                    values.put(line.substring(0, at), line.substring(at + 1));
                }
            }
        }
        return values;
    }

    private static String readOutput(Path childLog) throws IOException {
        return Files.exists(childLog) ? Files.readString(childLog) : "";
    }

    private static String describe(String what, Map<String, String> result) {
        return describe(what, result, "");
    }

    private static String describe(String what, Map<String, String> result, String output) {
        String merged = output.isEmpty() ? result.getOrDefault("childOutput", "") : output;
        return what + "\nchild result: " + result + "\nchild output:\n" + tail(merged);
    }

    private static String tail(String output) {
        return output.length() <= 8_000
                ? output
                : "... (truncated)\n" + output.substring(output.length() - 8_000);
    }

    @AfterEach
    void deleteCreatedDirectories() {
        createdDirectories.forEach(JGitShutdownRaceTest::deleteQuietly);
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup of temp directories
                }
            });
        } catch (IOException e) {
            // best-effort cleanup of temp directories
        }
    }
}
