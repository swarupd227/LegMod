package com.envestnet.atlas.gen.generator;

import com.sun.tools.ws.WsImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Invokes the JAX-WS Reference Implementation's wsimport tool programmatically
 * inside the gen-service JVM. Avoids needing a separate Docker sandbox for the
 * MVP — the JVM is the sandbox: temp directory under /tmp, no network from the
 * tool itself (we feed it a local WSDL).
 */
public class WsImportRunner {
    private static final Logger log = LoggerFactory.getLogger(WsImportRunner.class);

    public Result run(Path workDir, Path wsdlFile, Path bindingsFile,
                      String basePackage) throws Exception {
        Path srcOut = workDir.resolve("src");
        Files.createDirectories(srcOut);

        List<String> args = new ArrayList<>();
        args.add("-d"); args.add(srcOut.toString());
        args.add("-keep");
        args.add("-Xnocompile");
        args.add("-verbose");                                // surface "parsing WSDL", "generating code", etc.
        if (basePackage != null && !basePackage.isBlank()) {
            args.add("-p");
            args.add(basePackage);
        }
        if (bindingsFile != null && Files.exists(bindingsFile)) {
            args.add("-b");
            args.add(bindingsFile.toString());
        }
        args.add(wsdlFile.toString());

        long startMs = System.currentTimeMillis();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream prev = System.err;
        PrintStream prevOut = System.out;
        int code = -1;
        Throwable threw = null;
        try (PrintStream tee = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            tee.println("$ wsimport " + String.join(" ", args));
            tee.println();
            System.setErr(tee);
            System.setOut(tee);
            // WsImport.doMain declares `throws Throwable` to surface tool-internal
            // failures. We capture-and-continue so the caller still gets the
            // wsimport stdout/stderr instead of a generic 500.
            try {
                code = WsImport.doMain(args.toArray(new String[0]));
            } catch (Throwable t) {
                threw = t;
                tee.println("wsimport threw: " + t);
                t.printStackTrace(tee);
            }
        } finally {
            System.setErr(prev);
            System.setOut(prevOut);
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        StringBuilder logSb = new StringBuilder(captured.toString(StandardCharsets.UTF_8));
        // Always end with a clear summary line so a successful run never leaves
        // the Generation Log tab blank.
        logSb.append("\n--\n");
        logSb.append(String.format("wsimport exit code: %d  ·  elapsed: %d ms%n", code, elapsedMs));
        String log = logSb.toString();
        if (threw != null) {
            WsImportRunner.log.warn("wsimport threw — log:\n{}", log);
        }

        // Walk the generated tree.
        List<GeneratedFile> files = new ArrayList<>();
        if (Files.isDirectory(srcOut)) {
            Files.walkFileTree(srcOut, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        files.add(new GeneratedFile(
                                srcOut.relativize(file).toString().replace('\\', '/'),
                                (int) attrs.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        int errors = countMatches(log, "error:");
        int warnings = countMatches(log, "warning:");
        boolean ok = code == 0 && !files.isEmpty();

        WsImportRunner.log.info("wsimport done · code={} files={} errors={} warnings={}",
                code, files.size(), errors, warnings);
        return new Result(ok, code, files, errors, warnings, log, srcOut);
    }

    private int countMatches(String text, String token) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) { n++; idx += token.length(); }
        return n;
    }

    public record GeneratedFile(String path, int sizeBytes) {}
    public record Result(
            boolean ok,
            int exitCode,
            List<GeneratedFile> files,
            int errors,
            int warnings,
            String logText,
            Path sourceDir
    ) {}
}
