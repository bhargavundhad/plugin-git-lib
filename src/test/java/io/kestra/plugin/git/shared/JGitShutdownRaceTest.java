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
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * JGit shutdown-race regression harness (issue #23). The control test proves a
 * child JVM boots with the test runtime classpath, clones a local fixture
 * repository with the pinned JGit version, and reports its outcome through a
 * result file with a real, enforced process timeout. The race test starts the
 * JVM shutdown while the child's checkout holds the index lock — with the
 * JGitCleanupIsolation guard installed through the production initialization
 * point — and asserts the clone then completes instead of failing with "Lock on
 * <index> not held." because JGit's shutdown hook is prevented from removing
 * the live index lock during graceful shutdown. A guarded fetch case covers
 * issue #23's second failure mode — shutdown during fetch — asserting the clone
 * still completes because JGit's {@code CloneCommand.cleanup} listener is
 * suppressed. The unguarded fetch deletion itself is not asserted:
 * {@code cleanup()} is a best-effort recursive remove that silently ignores
 * failures, and a forked JVM suspended at the pre-write point cannot reliably
 * have its {@code .git} force-removed while the repository handles stay open
 * (notably on Windows). The unguarded checkout case already proves the
 * underlying shutdown cleanup genuinely fires and disrupts an in-flight
 * operation, so that mechanism is verified rather than assumed. The test does
 * not verify recovery after forced termination (SIGKILL); a broken, bypassed or
 * too-late guard re-exposes the failure and fails the test.
 */
class JGitShutdownRaceTest {

    private static final int FIXTURE_FILE_COUNT = 2_000;
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(120);

    private final List<Path> createdDirectories = new ArrayList<>();

    @AfterEach
    void deleteCreatedDirectories() {
        createdDirectories.forEach(JGitShutdownRaceTest::deleteQuietly);
    }

    @Test
    void childJvmClonesLocalFixtureWithTestRuntimeClasspath() throws Exception {
        Path argFile = Path.of(requiredProperty("io.kestra.git.test.classpathArgfile",
                "the test must be launched through Gradle; the property is set by the test task"));
        assertThat(childFailure("java @argfile with the test runtime classpath", "", Map.of()),
                Files.exists(argFile), is(true));

        Path remote = createDirectory("jgit-shutdown-race-remote-");
        createFixture(remote);
        Path target = createDirectory("jgit-shutdown-race-target-");
        Path scratch = createDirectory("jgit-shutdown-race-scratch-");
        Path resultFile = scratch.resolve("child-result.txt");
        Path childLog = scratch.resolve("child-output.log");

        ChildRun run = awaitChild(launchChild(argFile, childLog,
                "--source", remote.toUri().toString(),
                "--target", target.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString()), childLog, resultFile);

        if (!run.finished()) {
            fail(childFailure("child JVM exceeded " + CHILD_TIMEOUT + " and was destroyed", run.output(), run.result()));
        }
        assertThat(childFailure("child JVM exit code", run.output(), run.result()), run.exitCode(), is(0));
        assertThat(childFailure("child result outcome", run.output(), run.result()),
                run.result().get("outcome"), is("COMPLETED"));
        assertThat(childFailure("checked-out fixture file", run.output(), run.result()),
                // JGit's checkout applies native EOL conversion on Windows; compare content with normalized newlines.
                Files.readString(target.resolve("file-0001.txt")).replace("\r\n", "\n"), is("content 1\n"));
    }

    @Test
    void shutdownDuringGuardedCheckoutCompletesTheClone() throws Exception {
        Path argFile = Path.of(requiredProperty("io.kestra.git.test.classpathArgfile",
                "the test must be launched through Gradle; the property is set by the test task"));
        assertThat(childFailure("java @argfile with the test runtime classpath", "", Map.of()),
                Files.exists(argFile), is(true));

        Path remote = createDirectory("jgit-shutdown-race-remote-");
        createFixture(remote);
        Path target = createDirectory("jgit-shutdown-race-target-");
        Path scratch = createDirectory("jgit-shutdown-race-scratch-");
        Path resultFile = scratch.resolve("child-result.txt");
        Path childLog = scratch.resolve("child-output.log");

        ChildRun run = awaitChild(launchChild(argFile, childLog,
                "--source", remote.toUri().toString(),
                "--target", target.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString(),
                "--trigger", "checkout",
                "--guard", "on"), childLog, resultFile);

        if (!run.finished()) {
            fail(childFailure("child JVM exceeded " + CHILD_TIMEOUT + " and was destroyed", run.output(), run.result()));
        }
        assertThat(childFailure("child JVM exit code", run.output(), run.result()), run.exitCode(), is(0));
        assertThat(childFailure("child result outcome", run.output(), run.result()),
                run.result().get("outcome"), is("COMPLETED"));
        assertThat(childFailure("checkout trigger fired", run.output(), run.result()),
                run.result().get("checkoutTriggered"), is("true"));
        assertThat(childFailure("index lock confirmed before shutdown", run.output(), run.result()),
                run.result().get("lockConfirmed"), is("true"));
        assertThat(childFailure("index lock existed before wait", run.output(), run.result()),
                run.result().get("lockExistedBeforeWait"), is("true"));
        assertThat(childFailure("guard state", run.output(), run.result()),
                run.result().get("guardState"), is("ACTIVE"));
        assertThat(childFailure("index lock release observed during shutdown", run.output(), run.result()),
                run.result().get("lockReleasedObserved"), is("false"));
        assertThat(childFailure("index-lock wait timed out", run.output(), run.result()),
                run.result().get("lockStillPresentAfterWait"), is("true"));
        System.out.println("guard-enabled race result: " + run.result()); // evidence trail for the issue-#23 report
    }

    @Test
    void shutdownDuringUnguardedCheckoutReproducesTheIssue() throws Exception {
        Path argFile = Path.of(requiredProperty("io.kestra.git.test.classpathArgfile",
                "the test must be launched through Gradle; the property is set by the test task"));
        assertThat(childFailure("java @argfile with the test runtime classpath", "", Map.of()),
                Files.exists(argFile), is(true));

        Path remote = createDirectory("jgit-shutdown-race-remote-");
        createFixture(remote);
        Path target = createDirectory("jgit-shutdown-race-target-");
        Path scratch = createDirectory("jgit-shutdown-race-scratch-");
        Path resultFile = scratch.resolve("child-result.txt");
        Path childLog = scratch.resolve("child-output.log");

        ChildRun run = awaitChild(launchChild(argFile, childLog,
                "--source", remote.toUri().toString(),
                "--target", target.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString(),
                "--trigger", "checkout",
                "--guard", "off"), childLog, resultFile);

        if (!run.finished()) {
            fail(childFailure("child JVM exceeded " + CHILD_TIMEOUT + " and was destroyed", run.output(), run.result()));
        }
        assertThat(childFailure("child JVM exit code", run.output(), run.result()), run.exitCode(), is(0));
        assertThat(childFailure("child result outcome", run.output(), run.result()),
                run.result().get("outcome"), is("REPRODUCED_INDEX_UNLOCK"));
        assertThat(childFailure("checkout trigger fired", run.output(), run.result()),
                run.result().get("checkoutTriggered"), is("true"));
        assertThat(childFailure("index lock confirmed before shutdown", run.output(), run.result()),
                run.result().get("lockConfirmed"), is("true"));
        assertThat(childFailure("index lock existed before wait", run.output(), run.result()),
                run.result().get("lockExistedBeforeWait"), is("true"));
        assertThat(childFailure("guard state", run.output(), run.result()),
                run.result().get("guardState"), is("NOT_ATTEMPTED"));
        assertThat(childFailure("index lock release observed during shutdown", run.output(), run.result()),
                run.result().get("lockReleasedObserved"), is("true"));
        System.out.println("unguarded race result: " + run.result()); // evidence trail for the issue-#23 report
    }

    @Test
    void shutdownDuringGuardedFetchCompletesTheClone() throws Exception {
        Path argFile = Path.of(requiredProperty("io.kestra.git.test.classpathArgfile",
                "the test must be launched through Gradle; the property is set by the test task"));
        assertThat(childFailure("java @argfile with the test runtime classpath", "", Map.of()),
                Files.exists(argFile), is(true));

        Path remote = createDirectory("jgit-shutdown-race-remote-");
        createFixture(remote);
        Path target = createDirectory("jgit-shutdown-race-target-");
        Path scratch = createDirectory("jgit-shutdown-race-scratch-");
        Path resultFile = scratch.resolve("child-result.txt");
        Path childLog = scratch.resolve("child-output.log");

        ChildRun run = awaitChild(launchChild(argFile, childLog,
                "--source", remote.toUri().toString(),
                "--target", target.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString(),
                "--trigger", "fetch",
                "--guard", "on"), childLog, resultFile);

        if (!run.finished()) {
            fail(childFailure("child JVM exceeded " + CHILD_TIMEOUT + " and was destroyed", run.output(), run.result()));
        }
        assertThat(childFailure("child JVM exit code", run.output(), run.result()), run.exitCode(), is(0));
        assertThat(childFailure("child result outcome", run.output(), run.result()),
                run.result().get("outcome"), is("COMPLETED"));
        assertThat(childFailure("fetch trigger fired", run.output(), run.result()),
                run.result().get("fetchTriggered"), is("true"));
        assertThat(childFailure("partial .git confirmed before shutdown", run.output(), run.result()),
                run.result().get("gitDirConfirmed"), is("true"));
        assertThat(childFailure("partial .git existed before wait", run.output(), run.result()),
                run.result().get("gitDirExistedBeforeWait"), is("true"));
        assertThat(childFailure("guard state", run.output(), run.result()),
                run.result().get("guardState"), is("ACTIVE"));
        assertThat(childFailure("fetch cleanup ran during shutdown", run.output(), run.result()),
                run.result().get("fetchCleanupObserved"), is("false"));
        assertThat(childFailure("partial .git wait timed out (still present)", run.output(), run.result()),
                run.result().get("gitDirStillPresentAfterWait"), is("true"));
        System.out.println("guard-enabled fetch result: " + run.result()); // evidence trail for the issue-#23 report
    }

    private Process launchChild(Path argFile, Path childLog, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("@" + argFile.toAbsolutePath());
        command.add(JGitShutdownRaceChild.class.getName());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(childLog.toFile()) // file-backed: waitFor(timeout) in awaitChild actually enforces
                .start();
    }

    private record ChildRun(boolean finished, int exitCode, String output, Map<String, String> result) {

    }

    private static ChildRun awaitChild(Process child, Path childLog, Path resultFile)
            throws IOException, InterruptedException {
        boolean finished = child.waitFor(CHILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.exists(childLog) ? Files.readString(childLog) : "";
        Map<String, String> result = Files.exists(resultFile) ? readResultFile(resultFile) : Map.of();
        return new ChildRun(finished, finished ? child.exitValue() : -1, output, result);
    }

    private static String requiredProperty(String key, String why) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing system property " + key + " (" + why + ")");
        }
        return value;
    }

    private static String javaBinary() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private Path createDirectory(String prefix) throws IOException {
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

    private static Map<String, String> readResultFile(Path resultFile) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
            int at = line.indexOf('=');
            if (at > 0) {
                values.put(line.substring(0, at), line.substring(at + 1));
            }
        }
        return values;
    }

    private static String childFailure(String what, String output, Map<String, String> result) {
        return what + " | child output:\n" + trimOutput(output) + "\nchild result file: " + result;
    }

    private static String trimOutput(String output) {
        return output.length() <= 8_000 ? output : output.substring(0, 8_000) + "\n... (truncated)";
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
