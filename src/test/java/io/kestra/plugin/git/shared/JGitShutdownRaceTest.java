package io.kestra.plugin.git.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Regression test for #23: forks a child JVM that starts JVM shutdown while a clone is suspended at its critical
 * phase. Unguarded runs must reproduce the failure, guarded runs must complete with the on-disk state intact.
 */
class JGitShutdownRaceTest {

    private final List<Path> createdDirectories = new ArrayList<>();

    @ParameterizedTest(name = "{0}, guard {1} -> {2}")
    @CsvSource({
        "checkout, off, REPRODUCED_INDEX_UNLOCK, false",
        "checkout, on,  COMPLETED,               true",
        "fetch,    off, REPRODUCED_FETCH_CLEANUP, false",
        "fetch,    on,  COMPLETED,               true"
    })
    void shutdownDuringClone(String trigger, String guard, String outcome, boolean stateSurvived) throws Exception {
        // CloneCommand's best-effort delete of .git is not reliably observable on Windows.
        Assumptions.assumeFalse("fetch".equals(trigger) && "off".equals(guard) && isWindows(),
            "unguarded fetch cleanup is not observable on Windows");

        Path remote = tempDir();
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            for (int i = 0; i < 2_000; i++) {
                Files.writeString(remote.resolve("file-" + i + ".txt"), "content " + i);
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").call();
        }
        Path resultFile = tempDir().resolve("result.txt");
        Path log = resultFile.resolveSibling("child.log");

        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            JGitShutdownRaceChild.class.getName(),
            remote.toUri().toString(), tempDir().toString(), resultFile.toString(), trigger, guard)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(120, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }

        Map<String, String> result = new HashMap<>();
        if (Files.exists(resultFile)) {
            for (String line : Files.readAllLines(resultFile)) {
                result.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
            }
        }
        String why = "result: " + result + "\nchild log:\n" + Files.readString(log);

        assertThat(why, child.exitValue(), is(0));
        assertThat(why, result.get("outcome"), is(outcome));
        assertThat(why, result.get("stateSurvived"), is(String.valueOf(stateSurvived)));
        if ("REPRODUCED_INDEX_UNLOCK".equals(outcome)) {
            assertThat(why, result.get("message"), containsString("index not held"));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("jgit-shutdown-race-");
        createdDirectories.add(dir);
        return dir;
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path dir : createdDirectories) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
