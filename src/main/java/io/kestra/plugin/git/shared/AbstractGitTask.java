package io.kestra.plugin.git.shared;

import java.io.*;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.net.ssl.*;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.apache.HttpClientConnection;
import org.eclipse.jgit.transport.http.apache.HttpClientConnectionFactory;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.git.shared.services.JGitCleanupIsolation;
import io.kestra.plugin.git.shared.services.SshTransportConfigCallback;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

/**
 * Base class shared by every Kestra Git task (OSS and Enterprise Edition).
 *
 * <p>Holds connection, authentication and HTTP/SSL transport configuration common to clone, push and sync
 * operations. Concrete, registered tasks live in their own plugin repository (plugin-git / plugin-ee-git);
 * this class and its subclasses intentionally contain no {@code @Plugin}-annotated task.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
public abstract class AbstractGitTask extends Task {
    private static final Pattern PEBBLE_TEMPLATE_PATTERN = Pattern.compile("^\\s*\\{\\{");

    // Replaces the boolean flag with a configuration key to allow reconfiguration when the PEM changes.
    // Possible values: "JVM" or "PEM:<sha256-of-file-bytes>"
    private static final AtomicReference<String> SSL_CONFIGURED_KEY = new AtomicReference<>(null);
    private static final Object SSL_CONFIG_LOCK = new Object();

    // Namespace-first Git layout used by every namespace/tenant sync task: <namespace>/flows, <namespace>/files.
    private static final String FLOWS_DIR = "flows";
    private static final String FILES_DIR = "files";

    static {
        // Runs when the first task class initializes — every task base extends AbstractGitTask, so this precedes
        // this library's first JGit operation without needing static blocks in the service classes; see
        // JGitCleanupIsolation for scope, collateral effects and the failure policy.
        JGitCleanupIsolation.isolateSafely();
    }

    /**
     * Whether a namespace known only in Git carries content for a kind whose resolved source is GIT, narrowed
     * per kind so a namespace whose {@code files/} directory is Git-sourced doesn't get auto-created (or
     * otherwise pulled into the sync) from a stray {@code flows/} directory that stays Kestra-sourced, and
     * vice versa.
     */
    protected static boolean gitHasContent(Path namespaceRoot, SourceOfTruth flowsSource, SourceOfTruth filesSource) {
        return (flowsSource == SourceOfTruth.GIT && Files.isDirectory(namespaceRoot.resolve(FLOWS_DIR)))
            || (filesSource == SourceOfTruth.GIT && Files.isDirectory(namespaceRoot.resolve(FILES_DIR)));
    }

    @Schema(
        title = "Repository URL",
        description = "HTTP(S) or SSH URI used for clone and push operations."
    )
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(
        title = "Username or organization",
        description = "Used for HTTP basic authentication and as a fallback commit author."
    )
    @PluginProperty(secret = true, group = "connection")
    protected Property<String> username;

    @Schema(
        title = "Password or personal access token",
        description = """
            Supplies HTTP credentials. When a PAT is used, pushes are recorded under that PAT’s user without needing `authorName` and `authorEmail`.

            **GitHub PAT permissions required:**
            - Fine-grained PAT: `Contents: Read` (clone/fetch) or `Contents: Read and Write` (push), plus `Metadata: Read` (mandatory base permission). Add `Workflows: Read and Write` when pushing `.github/workflows/` files.
            - Classic PAT: `repo` scope covers all read/write operations; add `workflow` when pushing workflow files.
            """
    )
    @PluginProperty(secret = true, group = "connection")
    protected Property<String> password;

    @Schema(
        title = "PEM private key",
        description = "PEM-formatted private key matching a public key registered on the Git server. Generate with `ssh-keygen -t ecdsa -b 256 -m PEM`."
    )
    @PluginProperty(secret = true, group = "connection")
    protected Property<String> privateKey;

    @Schema(title = "Passphrase for `privateKey`")
    @PluginProperty(secret = true, group = "advanced")
    protected Property<String> passphrase;

    @Schema(
        title = "Whether to verify the SSH remote server's host key",
        description = "When enabled, the host key presented by the Git server is verified against `knownHosts` " +
            "(if provided) or the system/user known_hosts file. Disabling it exposes the connection to " +
            "man-in-the-middle attacks (CWE-297). Set `knownHosts` alongside this property for a hardened setup. " +
            "The default differs by edition: disabled (`false`) on Kestra OSS, enabled (`true`) on Kestra Enterprise Edition."
    )
    @PluginProperty(group = "advanced")
    protected Property<Boolean> strictHostKeyChecking;

    @Schema(
        title = "Known hosts file content used for SSH host key verification",
        description = "OpenSSH `known_hosts`-formatted content used to verify the remote server's SSH host key. " +
            "If not set, the system/user known_hosts file is used. Only relevant when `strictHostKeyChecking` is `true`."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> knownHosts;

    /**
     * The effective {@code strictHostKeyChecking} value when the property is left unset.
     *
     * <p>Kept as an overridable hook (rather than a hardcoded {@code @Builder.Default}) because the safe default
     * differs by edition: OSS never verified the host key ({@code false}, unchanged), while the Enterprise Edition
     * has always defaulted to verifying it ({@code true}, unchanged). A thin edition-specific base class overrides
     * this method instead of every concrete task re-declaring the property with a different default.
     */
    protected boolean defaultStrictHostKeyChecking() {
        return false;
    }

    @Schema(
        title = "Extra trusted CA PEM path",
        description = "Optional PEM-encoded CA bundle added to the JVM truststore; equivalent to `git config http.sslCAInfo <path>` for self-signed or internal CAs."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> trustedCaPemPath;

    @Schema(
        title = "Disable proxy for HTTP",
        description = "When true, forces direct connections instead of using the JVM proxy settings."
    )
    @PluginProperty(group = "advanced")
    protected Property<Boolean> noProxy;

    @Schema(title = "Initial Git branch")
    @PluginProperty(group = "advanced")
    public abstract Property<String> getBranch();

    @Schema(
        title = "HTTP connect timeout (ms)",
        description = "Default 10000 ms.",
        defaultValue = "10000"
    )
    @PluginProperty(group = "execution")
    protected Property<Integer> connectTimeout;

    @Schema(
        title = "HTTP read timeout (ms)",
        description = "Default 60000 ms.",
        defaultValue = "60000"
    )
    @PluginProperty(group = "execution")
    protected Property<Integer> readTimeout;

    @Schema(
        title = "Git configuration overrides",
        description = """
            Map of git config keys and values applied after clone, e.g.:
            - core.fileMode: false (ignore permission flips)
            - core.autocrlf: false (preserve line endings)
            """
    )
    @PluginProperty(group = "advanced")
    protected Property<Map<String, Object>> gitConfig;

    /**
     * Whether {@link #configureHttpTransport(RunContext)} should always install the connection factory, even when
     * {@code noProxy}/{@code connectTimeout}/{@code readTimeout} are all left unset.
     *
     * <p>Defaults to {@code true}: OSS has always installed the factory unconditionally, applying its 10s/60s
     * connect/read timeout defaults to every clone/push/sync. Preserving that keeps an OSS clone against an
     * unresponsive host timing out rather than hanging on jgit's/JDK's infinite defaults. The Enterprise Edition,
     * whose task family never called this method before the shared-kernel extraction, overrides this to {@code false}
     * so it does not mutate the process-wide default connection factory when nothing is configured.
     */
    protected boolean alwaysConfigureHttpTransport() {
        return true;
    }

    /**
     * Installs a JVM-global {@link HttpTransport} connection factory to apply {@code noProxy}/{@code connectTimeout}
     * /{@code readTimeout}. When {@link #alwaysConfigureHttpTransport()} is {@code false} (the Enterprise Edition),
     * this method is a no-op unless at least one of the three is configured, so an EE task that leaves them all unset
     * never mutates the process-wide default connection factory.
     */
    protected void configureHttpTransport(RunContext runContext) throws Exception {
        final boolean rNoProxy = this.noProxy != null && runContext.render(this.noProxy).as(Boolean.class).orElse(false);

        if (!alwaysConfigureHttpTransport() && !rNoProxy && this.connectTimeout == null && this.readTimeout == null) {
            return;
        }

        final Integer rConnectTimeout = this.connectTimeout == null ? 10000 : runContext.render(this.connectTimeout).as(Integer.class).orElse(10000);
        final Integer rReadTimeout = this.readTimeout == null ? 60000 : runContext.render(this.readTimeout).as(Integer.class).orElse(60000);

        runContext.logger().debug("Configured with noProxy: {}", rNoProxy);
        HttpTransport.setConnectionFactory(new HttpClientConnectionFactory() {
            @Override
            public HttpConnection create(URL url, Proxy proxy) throws IOException {
                if (rNoProxy) {
                    HttpClientConnection httpClientConnection = new HttpClientConnection(url.toString(), Proxy.NO_PROXY);
                    httpClientConnection.setConnectTimeout(rConnectTimeout);
                    httpClientConnection.setReadTimeout(rReadTimeout);
                    return httpClientConnection;
                } else {
                    HttpConnection httpConnection = super.create(url, proxy);
                    httpConnection.setConnectTimeout(rConnectTimeout);
                    httpConnection.setReadTimeout(rReadTimeout);
                    return httpConnection;
                }
            }
        });
    }

    /**
     * Installs a composite trust manager (PEM trust + JVM trust) as the JVM-global default SSLContext when
     * {@code trustedCaPemPath} is set. This method is a no-op when no custom CA is configured: the shared kernel
     * is compiled separately into the OSS and Enterprise Edition jars, each with its own {@link #SSL_CONFIGURED_KEY}
     * cache, but both mutate the same JVM-global default SSLContext ({@link SSLContext#setDefault}). Resetting it
     * to the JVM default here would let a task in one edition silently undo a custom CA installed by a task in the
     * other edition. Idempotent for the custom-CA case: reconfigures if and only if the PEM content changes.
     */
    protected void configureEnvironmentWithSsl(RunContext runContext) throws Exception {
        if (trustedCaPemPath == null) {
            return;
        }
        String pemPath = runContext.render(trustedCaPemPath).as(String.class).orElse(null);
        if (pemPath == null || pemPath.isBlank()) {
            return;
        }

        String desiredKey = computeDesiredSslKey(pemPath);

        // Fast-path: already configured with this key
        if (desiredKey.equals(SSL_CONFIGURED_KEY.get())) {
            runContext.logger().debug("SSLContext already configured with key: {}", desiredKey);
            return;
        }

        synchronized (SSL_CONFIG_LOCK) {
            if (desiredKey.equals(SSL_CONFIGURED_KEY.get())) {
                return;
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");

            // Build composite TrustManager: [custom-from-PEM, jvm-default]
            X509TrustManager customTm = buildTrustManagerFromPem(Path.of(pemPath));
            X509TrustManager jvmTm = buildJvmDefaultTrustManager();
            X509TrustManager composite = new CompositeX509TrustManager(List.of(customTm, jvmTm));
            sslContext.init(null, new TrustManager[] { composite }, new SecureRandom());
            runContext.logger().info("Configured SSLContext with PEM: {}", pemPath);

            // Apply as global defaults for JGit/HttpClient
            SSLContext.setDefault(sslContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            // Keep default HostnameVerifier (strict)

            SSL_CONFIGURED_KEY.set(desiredKey);
            runContext.logger().debug("SSL configured key now: {}", desiredKey);
        }
    }

    // Builds a key representing the desired SSL configuration: "PEM:<sha256-of-bytes>" of the trusted CA file.
    private static String computeDesiredSslKey(String pemPath) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(pemPath));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return "PEM:" + Base64.getEncoder().encodeToString(digest);
    }

    private static X509TrustManager buildJvmDefaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // use default JVM truststore
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager found in JVM default TrustManagerFactory");
    }

    private static X509TrustManager buildTrustManagerFromPem(Path pemFile) throws Exception {
        byte[] bytes = Files.readAllBytes(pemFile);

        // Try to load one or multiple certs from the PEM
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<X509Certificate> certs = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            // Works for single PEM and also multiple certs concatenated in many JDKs
            var cert = (X509Certificate) cf.generateCertificate(bais);
            certs.add(cert);
            while (bais.available() > 0) {
                X509Certificate c = (X509Certificate) cf.generateCertificate(bais);
                if (c != null)
                    certs.add(c);
            }
        } catch (Exception e) {
            // Fallback: try generateCertificates (PKCS#7 or multiple certs)
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                for (var c : cf.generateCertificates(bais)) {
                    certs.add((X509Certificate) c);
                }
            }
        }

        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate found in PEM file: " + pemFile);
        }

        // Put certs in an in-memory KeyStore
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        int i = 0;
        for (X509Certificate c : certs) {
            ks.setCertificateEntry("custom-ca-" + (i++), c);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager found in custom TrustManagerFactory from PEM");
    }

    /**
     * Simple composite X509TrustManager that tries each delegate in order and succeeds if any trusts the chain.
     */
    private static final class CompositeX509TrustManager implements X509TrustManager {
        private final List<X509TrustManager> delegates;

        private CompositeX509TrustManager(List<X509TrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager tm : delegates) {
                try {
                    tm.checkClientTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException e) {
                    last = e;
                }
            }
            throw (last != null) ? last : new java.security.cert.CertificateException("No trust manager accepted client chain");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager tm : delegates) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException e) {
                    last = e;
                }
            }
            throw (last != null) ? last : new java.security.cert.CertificateException("No trust manager accepted server chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager tm : delegates) {
                for (X509Certificate c : tm.getAcceptedIssuers()) {
                    all.add(c);
                }
            }
            return all.toArray(new X509Certificate[0]);
        }
    }

    public <T extends TransportCommand<T, ?>> T authentified(T command, RunContext runContext) throws Exception {
        if (this.username != null && this.password != null) {
            command.setCredentialsProvider(
                new UsernamePasswordCredentialsProvider(
                    runContext.render(this.username).as(String.class).orElseThrow(),
                    runContext.render(this.password).as(String.class).orElseThrow()
                )
            );
        }

        if (this.privateKey != null) {
            command.setTransportConfigCallback(
                new SshTransportConfigCallback(
                    runContext.render(this.privateKey).as(String.class).orElseThrow().getBytes(StandardCharsets.UTF_8),
                    runContext.render(this.passphrase).as(String.class).orElse(null),
                    runContext.render(this.strictHostKeyChecking).as(Boolean.class).orElseGet(this::defaultStrictHostKeyChecking),
                    runContext.render(this.knownHosts).as(String.class).orElse(null)
                )
            );
        }

        return command;
    }

    /**
     * Apply Git configuration settings to a repository
     */
    public void applyGitConfig(Repository repository, RunContext runContext) throws Exception {
        Map<String, Object> rGitConfig = runContext.render(this.gitConfig).asMap(String.class, Object.class);
        if (rGitConfig.isEmpty()) {
            return;
        }

        StoredConfig gitRepoConfig = repository.getConfig();

        for (Map.Entry<String, Object> entry : rGitConfig.entrySet()) {
            String key = entry.getKey();
            Object entryValue = entry.getValue();

            String[] parts = key.split("\\.");
            if (parts.length < 2) {
                // The name is actually the section and the key separated by a dot.
                // Example: "core.fileMode" -> section = "core", name = "fileMode"
                runContext.logger().warn(
                    "invalid git gitRepoConfig key format: {}. The name is actually the section and the key separated by a dot. " +
                        "Expected 'section.name' or 'section.subsection.name'",
                    key
                );
                continue;
            }

            String section = parts[0].toLowerCase(Locale.ROOT);
            String name = parts[parts.length - 1];
            String subsection = (parts.length > 2)
                ? String.join(".", Arrays.copyOfRange(parts, 1, parts.length - 1))
                : null;

            if (entryValue == null || (entryValue instanceof String s && s.trim().isEmpty())) {
                gitRepoConfig.unset(section, subsection, name);
                runContext.logger().info("Unset git gitRepoConfig {}", key);
                continue;
            }

            switch (entryValue) {
                case Boolean b -> gitRepoConfig.setBoolean(section, subsection, name, b);

                case Number n -> {
                    long lValue = n.longValue();
                    if (n.doubleValue() == (double) lValue && lValue >= Integer.MIN_VALUE && lValue <= Integer.MAX_VALUE) {
                        gitRepoConfig.setInt(section, subsection, name, (int) lValue);
                    } else {
                        gitRepoConfig.setString(section, subsection, name, n.toString());
                    }
                }

                case Collection<?> col -> {
                    List<String> values = col.stream().map(String::valueOf).toList();
                    gitRepoConfig.setStringList(section, subsection, name, values);
                }

                case String s -> {
                    String trimmed = s.trim();
                    gitRepoConfig.setString(section, subsection, name, trimmed);
                }

                default -> gitRepoConfig.setString(section, subsection, name, entryValue.toString());
            }

            runContext.logger().info("Applied git config {} = {}", key, entryValue);
        }

        gitRepoConfig.save();
        runContext.logger().info("Applied {} git configuration settings", rGitConfig.size());
    }

    protected URI createIonDiff(RunContext runContext, Git git) throws IOException, GitAPIException {
        File diffFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (
            BufferedWriter diffWriter = new BufferedWriter(new FileWriter(diffFile));
            DiffFormatter diffFormatter = new DiffFormatter(new ByteArrayOutputStream())
        ) {

            diffFormatter.setRepository(git.getRepository());

            // we check if the HEAD is null to handle the initial commit, if it is null we use an empty tree iterator
            var diff = git.diff().setCached(true);
            if (git.getRepository().resolve(Constants.HEAD) == null) {
                diff.setOldTree(new EmptyTreeIterator());
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonFactory factory = mapper.getFactory();
            try (JsonGenerator generator = factory.createGenerator(diffWriter)) {
                for (DiffEntry de : diff.call()) {
                    EditList editList = diffFormatter.toFileHeader(de).toEditList();
                    int additions = 0, deletions = 0, changes = 0;

                    for (Edit edit : editList) {
                        int mods = edit.getLengthB() - edit.getLengthA();
                        if (mods > 0)
                            additions += mods;
                        else if (mods < 0)
                            deletions += -mods;
                        else
                            changes += edit.getLengthB();
                    }

                    generator.writeStartObject();
                    generator.writeStringField("file", getPath(de));
                    generator.writeNumberField("additions", additions);
                    generator.writeNumberField("deletions", deletions);
                    generator.writeNumberField("changes", changes);
                    generator.writeEndObject();
                    generator.writeRaw('\n');
                }
            }
        }

        return runContext.storage().putFile(diffFile);
    }

    private static String getPath(DiffEntry diffEntry) {
        return diffEntry.getChangeType() == DiffEntry.ChangeType.DELETE
            ? diffEntry.getOldPath()
            : diffEntry.getNewPath();
    }

    protected String buildCommitUrl(String httpUrl, String branch, String commitId) {

        if (commitId == null || httpUrl == null) {
            return null;
        }

        // Clean URL (remove .git if present)
        httpUrl = httpUrl.replaceAll("\\.git$", "");

        String commitSubroute = httpUrl.contains("bitbucket.org") ? "commits" : "commit";
        String commitUrl = httpUrl + "/" + commitSubroute + "/" + commitId;

        if (httpUrl.contains("azure.com")) {
            commitUrl += "?refName=refs%2Fheads%2F" + branch;
        }

        return commitUrl;
    }

    // Public so that concrete tasks in other packages/repos (e.g. NamespaceSync, TenantSync) can reference it;
    // they extend this hierarchy but live outside io.kestra.plugin.git.shared.
    @Getter
    @AllArgsConstructor
    public static class DiffLine {
        private String file;
        private String key;
        private Kind kind;
        private Action action;

        public static DiffLine added(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.ADDED);
        }

        public static DiffLine updatedGit(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.UPDATED_GIT);
        }

        public static DiffLine updatedKestra(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.UPDATED_KES);
        }

        public static DiffLine unchanged(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.UNCHANGED);
        }

        public static DiffLine deletedGit(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.DELETED_GIT);
        }

        public static DiffLine deletedKestra(String file, String key, Kind kind) {
            return new DiffLine(file, key, kind, Action.DELETED_KES);
        }

        @SneakyThrows
        public static URI writeIonFile(RunContext runContext, List<DiffLine> diffs) {
            List<DiffLine> changed = diffs.stream()
                .filter(d -> d.getAction() != Action.UNCHANGED)
                .toList();

            byte[] ion = JacksonMapper.ofIon().writeValueAsBytes(changed);
            try (ByteArrayInputStream input = new ByteArrayInputStream(ion)) {
                return runContext.storage().putFile(input, "diffs.ion");
            }
        }
    }

    /**
     * {@code DASHBOARD}, {@code APP}, {@code TEST} and {@code BLUEPRINT} are only produced by the Enterprise
     * Edition's namespace/tenant sync tasks; kept here as a superset so the shared {@link DiffLine} type covers
     * every resource kind either edition can report.
     */
    public enum Kind {
        FLOW,
        FILE,
        DASHBOARD,
        APP,
        TEST,
        BLUEPRINT
    }

    public enum Action {
        ADDED,
        UPDATED_GIT,
        UPDATED_KES,
        UNCHANGED,
        DELETED_GIT,
        DELETED_KES
    }
}
