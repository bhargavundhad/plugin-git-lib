package io.kestra.plugin.git.shared.services;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@KestraTest
class CloneServiceTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void clonesIntoAnEmptyDirectory() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .cloneAllBranches(true)
            .build());

        assertThat(Files.exists(Path.of(result.directory()).resolve("file.txt")), is(true));
    }

    @Test
    void clonesAtASpecificCommit() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        String firstCommitSha;
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file1.txt"), "first\n");
            git.add().addFilepattern("file1.txt").call();
            git.commit().setMessage("first").call();
            firstCommitSha = git.getRepository().resolve("HEAD").name();

            Files.writeString(remote.resolve("file2.txt"), "second\n");
            git.add().addFilepattern("file2.txt").call();
            git.commit().setMessage("second").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .commit(firstCommitSha)
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.exists(repoPath.resolve("file1.txt")), is(true));
        assertThat(Files.exists(repoPath.resolve("file2.txt")), is(false));
    }

    @Test
    void clonesIntoANonEmptyDirectoryUsingInitFetchCheckout() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("repo-file.txt"), "from repo\n");
            git.add().addFilepattern("repo-file.txt").call();
            git.commit().setMessage("initial").call();
        }

        RunContext runContext = runContextFactory.of();
        Path workingDir = runContext.workingDir().path();
        Files.writeString(workingDir.resolve("pre-existing.txt"), "I was here first\n");

        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(workingDir)
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.readString(repoPath.resolve("pre-existing.txt")), is("I was here first\n"));
        assertThat(Files.readString(repoPath.resolve("repo-file.txt")), is("from repo\n"));
    }

    @Test
    void clonesOnlyTheConfiguredBranch() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            String initialBranch = git.getRepository().getBranch();

            Files.writeString(remote.resolve("main.txt"), "main\n");
            git.add().addFilepattern("main.txt").call();
            git.commit().setMessage("main").call();

            git.checkout().setCreateBranch(true).setName("feature/only").call();
            Files.writeString(remote.resolve("feature.txt"), "feature\n");
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("feature").call();

            git.checkout().setCreateBranch(true).setName("extra/branch").call();
            Files.writeString(remote.resolve("extra.txt"), "extra\n");
            git.add().addFilepattern("extra.txt").call();
            git.commit().setMessage("extra").call();

            git.checkout().setName(initialBranch).call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .branch("feature/only")
            .cloneAllBranches(false)
            .build());

        try (Git cloned = Git.open(Path.of(result.directory()).toFile())) {
            assertNotNull(cloned.getRepository().exactRef("refs/remotes/origin/feature/only"));
            assertNull(cloned.getRepository().exactRef("refs/remotes/origin/extra/branch"));
        }
    }

    /**
     * {@code CloneService.clone} is the single source of the "Start cloning" log line; the consumer {@code Clone}
     * tasks (plugin-git, plugin-ee-git) must not log it a second time, otherwise every clone logs it twice.
     */
    @Test
    void logsStartCloningExactlyOnce() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        Logger logbackLogger = (Logger) runContext.logger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                .url(remote.toUri().toString())
                .path(runContext.workingDir().path())
                .cloneAllBranches(true)
                .build());
        } finally {
            logbackLogger.detachAppender(appender);
        }

        long startCloningLogCount = appender.list.stream()
            .filter(event -> event.getFormattedMessage().startsWith("Start cloning"))
            .count();

        assertThat(startCloningLogCount, is(1L));
    }
}
