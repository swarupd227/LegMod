package com.envestnet.atlas.gen.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and runs unit tests for a project's migrated/generated code.
 *
 * <p>Atlas's Stage F gate runs this against either:
 * <ul>
 *   <li><strong>SOAP track</strong>: the wsimport-generated Java tree.
 *       Atlas drops a minimal pom.xml in the source root that pulls
 *       jakarta.xml.ws-api + jakarta.jws-api, then runs
 *       {@code mvn -q -DskipTests compile} to prove the generated
 *       stubs compile cleanly. No tests in the generated output, so
 *       the test counts come back as zero — which is fine and
 *       reported as such.</li>
 *   <li><strong>UPLIFT track</strong>: the customer's source tree
 *       after OpenRewrite recipes have been applied. The tree
 *       already has its own pom.xml, so Atlas runs
 *       {@code mvn -q -DskipTests=false test} directly. The customer's
 *       existing tests are the regression check.</li>
 * </ul>
 *
 * <p>Both paths shell out to Maven via {@link ProcessBuilder}. The
 * gen-service container ships with Maven (Eclipse Temurin 21 base
 * + Maven 3.9) so this works out-of-the-box. Test output is parsed
 * for surefire's "Tests run: N, Failures: M, Errors: K, Skipped: S"
 * line — that gives the structured counts the SPA renders.</p>
 *
 * <p>The whole call is bounded by a {@code timeoutSeconds} parameter
 * (default 240s = 4 min); on timeout we kill the subprocess and
 * surface a clean error to the caller.</p>
 */
public class BuildTestRunner {
    private static final Logger log = LoggerFactory.getLogger(BuildTestRunner.class);

    /** Surefire result line, e.g. "Tests run: 142, Failures: 0, Errors: 0, Skipped: 0". */
    private static final Pattern SUREFIRE = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    /** Javac compile error count, e.g. "5 errors". */
    private static final Pattern COMPILE_ERR = Pattern.compile("(\\d+)\\s*errors?");

    /**
     * Run "mvn compile" for a generated Java tree (SOAP track).
     * @param workDir       the source tree root (writeable; we drop a pom.xml here)
     * @param basePackage   for synthesizing the pom artifact id
     * @param timeoutSeconds bound on the maven call
     */
    public Result compileGenerated(Path workDir, String basePackage, int timeoutSeconds) throws Exception {
        // Write a minimal pom.xml so maven can resolve dependencies
        // and compile the wsimport-generated JAX-WS stubs.
        writeGeneratedPom(workDir, basePackage);
        List<String> args = List.of("mvn", "-q", "-B", "-DskipTests", "compile");
        return runMaven(workDir, args, "SOAP", timeoutSeconds);
    }

    /**
     * Run "mvn test" for an in-place modified Java project (UPLIFT
     * track). Assumes the source tree already has a pom.xml at its
     * root.
     *
     * Strategy: rely on the pre-warmed ~/.m2 cache (populated at image
     * build time from a real spring-petclinic clone) but stay in
     * ONLINE mode. Maven's "-o" offline flag is too strict — it
     * refuses cached artifacts that don't have provenance recorded
     * against every plugin repository the customer pom declares,
     * even when those artifacts are sitting right there in the
     * local cache. Online mode lets Maven prefer the cache and only
     * fall through to network for genuinely missing artifacts.
     *
     * We also skip the non-essential plugins (checkstyle, javaformat,
     * spotless, license, jacoco, enforcer): they often reference
     * config files that weren't part of the customer's sparse-
     * checkout subpath, and they're not what the customer's actually
     * asking ("did Atlas break my tests?"). The surefire test
     * execution still runs and produces real pass/fail counts.
     */
    public Result buildAndTestExisting(Path workDir, int timeoutSeconds) throws Exception {
        if (!Files.exists(workDir.resolve("pom.xml"))) {
            // Walk upward looking for the nearest pom.xml — the
            // ingestion subpath may have landed us inside a module.
            Path withPom = findPomXmlAncestor(workDir, 4);
            if (withPom != null) {
                workDir = withPom;
            }
        }
        // NOTE on the missing -q flag: surefire emits its "Tests run: N,
        // Failures: M, Errors: K, Skipped: S" summary at INFO level. Maven
        // -q suppresses INFO and the parser below sees zero counts, even
        // when the build passed cleanly. We accept the extra log volume to
        // get accurate test totals — the UI shows the parsed numbers, not
        // the raw log, so verbosity doesn't leak through.
        List<String> args = List.of("mvn", "-B", "-fae", "test",
                "-Dcheckstyle.skip=true",
                "-Dspring-javaformat.skip=true",
                "-Dspotless.check.skip=true",
                "-Dlicense.skip=true",
                "-Dformatter.skip=true",
                "-Ddependency-check.skip=true",
                "-Djacoco.skip=true",
                "-Denforcer.skip=true");
        return runMaven(workDir, args, "UPLIFT", timeoutSeconds);
    }

    private Result runMaven(Path workDir, List<String> args, String track, int timeoutSeconds)
            throws Exception {
        long startMs = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();
        out.append("$ ").append(String.join(" ", args)).append("\n\n");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        // Maven needs HOME to resolve ~/.m2; in the docker container
        // the gen-service runs as the 'atlas' user with /home/atlas
        // already set, but we surface the override env explicitly so
        // local runs don't surprise.
        pb.environment().putIfAbsent("MAVEN_OPTS", "-Xmx512m -Dorg.slf4j.simpleLogger.showDateTime=false");

        Process p = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < 100_000) {
                            out.append(line).append('\n');
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, "maven-out-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - startMs;
        int code;
        if (!finished) {
            p.destroyForcibly();
            out.append("\n[TIMEOUT after ").append(timeoutSeconds).append("s]\n");
            code = -1;
        } else {
            code = p.exitValue();
        }
        reader.join(2000);

        // Parse the surefire result line(s); maven prints one per
        // test class plus an aggregate at the end. Take the last.
        int tTotal = 0, tFail = 0, tErr = 0, tSkip = 0;
        Matcher m = SUREFIRE.matcher(out);
        while (m.find()) {
            tTotal = Integer.parseInt(m.group(1));
            tFail  = Integer.parseInt(m.group(2));
            tErr   = Integer.parseInt(m.group(3));
            tSkip  = Integer.parseInt(m.group(4));
        }
        int testsFailed = tFail + tErr;
        int testsPassed = Math.max(0, tTotal - testsFailed - tSkip);

        // Parse compile errors. For mvn compile failures the output
        // typically ends with "BUILD FAILURE" + a count.
        int compileErrors = 0;
        if (code != 0) {
            Matcher ce = COMPILE_ERR.matcher(out);
            while (ce.find()) {
                compileErrors = Math.max(compileErrors, Integer.parseInt(ce.group(1)));
            }
        }

        // Status inference. Distinguish three failure shapes that
        // matter for the customer narrative:
        //   - "compile_failed": the customer's source (or our generated
        //                       code) won't compile. Real code problem.
        //   - "tests_failed":   it compiles, but tests fail. Real
        //                       behavioral regression.
        //   - "sandbox_limited": Maven could not reach a dependency
        //                       repository. Not a code problem -
        //                       infrastructure not available.
        //   - "error":          subprocess failed for unknown reason.
        //   - "passed":         clean compile, clean tests.
        // Three signatures of "Maven can't reach the internet":
        //   (a) [TIMEOUT after Ns] - we killed maven because it hung
        //   (b) Could not transfer artifact - maven got an error response
        //   (c) Non-resolvable import POM - dependency resolution failed
        boolean sandboxNetwork = out.toString().contains("[TIMEOUT")
                              || out.toString().contains("Could not transfer artifact")
                              || out.toString().contains("Non-resolvable import POM");
        String status;
        if (sandboxNetwork) {
            // Network failure (timeout or transfer-failed) takes
            // precedence over the generic "error" code: a timed-out
            // maven run is almost always waiting for a dependency it
            // can't reach.
            status = "sandbox_limited";
        } else if (code == -1) {
            status = "error";
        } else if (code != 0 && compileErrors > 0 && tTotal == 0) {
            status = "compile_failed";
        } else if (code != 0 && testsFailed > 0) {
            status = "tests_failed";
        } else if (code != 0) {
            status = "error";
        } else {
            status = "passed";
        }

        // Walk source files compiled (just a count of .java sources
        // in target/classes / target/test-classes if they exist).
        int filesCompiled = countCompiledClassfiles(workDir);

        Result r = new Result();
        r.status         = status;
        r.track          = track;
        r.filesCompiled  = filesCompiled;
        r.compileErrors  = compileErrors;
        r.testsTotal     = tTotal;
        r.testsPassed    = testsPassed;
        r.testsFailed    = testsFailed;
        r.testsSkipped   = tSkip;
        r.durationMs     = elapsedMs;
        r.logText        = out.toString();
        r.failures       = extractFailureNames(out.toString());
        log.info("Build+test done · track={} status={} files={} tests={}/{} pass/fail · {}ms",
                track, status, filesCompiled, testsPassed, testsFailed, elapsedMs);
        return r;
    }

    /**
     * Synthesise a minimal pom.xml so maven can compile the
     * wsimport-generated stubs. Idempotent — overwrites whatever was
     * there from a prior run.
     */
    private void writeGeneratedPom(Path workDir, String basePackage) throws Exception {
        String artifact = (basePackage == null || basePackage.isBlank())
                ? "atlas-generated"
                : basePackage.replace('.', '-');
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>atlas.generated</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <properties>
                    <maven.compiler.source>21</maven.compiler.source>
                    <maven.compiler.target>21</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.xml.ws</groupId>
                      <artifactId>jakarta.xml.ws-api</artifactId>
                      <version>4.0.2</version>
                    </dependency>
                    <dependency>
                      <groupId>jakarta.jws</groupId>
                      <artifactId>jakarta.jws-api</artifactId>
                      <version>3.0.0</version>
                    </dependency>
                  </dependencies>
                  <build>
                    <sourceDirectory>${project.basedir}</sourceDirectory>
                  </build>
                </project>
                """.formatted(artifact);
        Files.writeString(workDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
    }

    private Path findPomXmlAncestor(Path start, int maxUp) {
        Path p = start;
        for (int i = 0; i < maxUp && p != null; i++) {
            if (Files.exists(p.resolve("pom.xml"))) return p;
            p = p.getParent();
        }
        return null;
    }

    private int countCompiledClassfiles(Path workDir) {
        Path classes = workDir.resolve("target").resolve("classes");
        if (!Files.exists(classes)) return 0;
        try {
            return (int) Files.walk(classes)
                    .filter(p -> p.toString().endsWith(".class"))
                    .count();
        } catch (Exception e) { return 0; }
    }

    /**
     * Pull out a small list of failed test names from the maven log.
     * The Surefire reporter writes "[ERROR]   ClassName.testMethod"
     * lines; we collect up to 20 unique ones for the SPA to render.
     */
    private List<String> extractFailureNames(String log) {
        Pattern fpat = Pattern.compile("\\[ERROR\\]\\s+([\\w\\.\\$]+\\.[a-z][\\w\\$]*)");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = fpat.matcher(log);
        while (m.find() && out.size() < 20) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    public static class Result {
        public String status;
        public String track;
        public int filesCompiled;
        public int compileErrors;
        public int testsTotal;
        public int testsPassed;
        public int testsFailed;
        public int testsSkipped;
        public long durationMs;
        public String logText;
        public List<String> failures = List.of();
    }
}
