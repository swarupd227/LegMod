package com.envestnet.atlas.uplift.scan;

import com.envestnet.atlas.uplift.rules.DetectionRule;
import com.envestnet.atlas.uplift.rules.DetectionRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Walks a Java source tree, groups files into modules (= sub-package directly
 * under the project's main package), and applies {@link DetectionRules#ALL}
 * to each line. Output is a structural model the persistence layer can write
 * verbatim — no DB coupling here.
 */
public class HeatmapScanner {
    private static final Logger log = LoggerFactory.getLogger(HeatmapScanner.class);

    public Result scan(Path sourceRoot) throws IOException {
        Result r = new Result();
        if (!Files.isDirectory(sourceRoot)) return r;

        // Find every .java under src/main/java; tolerate nested project layouts.
        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            javaFiles = s
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                .toList();
        }
        if (javaFiles.isEmpty()) {
            // fall back to any .java under root
            try (Stream<Path> s = Files.walk(sourceRoot)) {
                javaFiles = s.filter(p -> p.toString().endsWith(".java")).toList();
            }
        }
        log.info("scanned tree {} -> {} java files", sourceRoot, javaFiles.size());

        Map<String, Module> byPackage = new LinkedHashMap<>();
        for (Path p : javaFiles) {
            String pkg = packageOf(p);
            String moduleName = moduleNameOf(pkg);
            Module m = byPackage.computeIfAbsent(moduleName, k -> {
                Module mm = new Module();
                mm.name = k;
                mm.packageName = pkg;
                return mm;
            });

            String text;
            try { text = Files.readString(p); } catch (IOException e) { continue; }
            m.fileCount++;

            String[] lines = text.split("\\r?\\n");
            int loc = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                        || trimmed.startsWith("/*")) continue;
                loc++;

                for (DetectionRule rule : DetectionRules.ALL) {
                    Matcher mm = rule.pattern.matcher(line);
                    if (mm.find()) {
                        Finding f = new Finding();
                        f.ruleId = rule.id;
                        f.ruleLabel = rule.label;
                        f.severity = rule.severity;
                        f.filePath = sourceRoot.relativize(p).toString().replace('\\', '/');
                        f.lineStart = i + 1;
                        f.lineEnd = i + 1;
                        f.snippet = trimmed;
                        f.suggestedRecipe = rule.suggestedRecipe;
                        f.weight = rule.weight;
                        m.findings.add(f);
                    }
                }
            }
            m.loc += loc;
        }

        // Compute per-module difficulty 0-10.
        for (Module m : byPackage.values()) {
            int weighted = m.findings.stream().mapToInt(f -> f.weight).sum();
            // 5 weighted hits = difficulty 6, 10 = 8, 20+ = 10. Cap and floor.
            int score = (int) Math.min(10, Math.round(weighted * 1.2));
            if (score == 0 && m.fileCount > 0) score = 1; // minimum visible
            m.difficulty = score;
            r.totalFindings += m.findings.size();
        }

        r.modules.addAll(byPackage.values());
        r.modules.sort(Comparator.comparingInt((Module m) -> -m.difficulty)
                .thenComparingInt(m -> -m.loc));

        log.info("scan done · modules={} findings={}",
                r.modules.size(), r.totalFindings);
        return r;
    }

    /* ---------------- helpers ---------------- */

    /** Read package declaration from the file's first non-blank lines. */
    private static String packageOf(Path p) {
        try {
            for (String line : Files.readAllLines(p)) {
                String t = line.trim();
                if (t.startsWith("package ") && t.endsWith(";")) {
                    return t.substring("package ".length(), t.length() - 1).trim();
                }
                if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("*")
                        && !t.startsWith("/*") && !t.startsWith("import")) {
                    break;
                }
            }
        } catch (IOException ignored) {}
        return "(default)";
    }

    /**
     * Module name = leaf segment of the package — "web", "service", "domain".
     * The convention matches typical layered Spring projects and gives the
     * heatmap meaningful tiles without needing a Maven multi-module setup.
     * If the package is the top-level project package itself, return "root".
     */
    private static String moduleNameOf(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals("(default)")) return "root";
        int idx = pkg.lastIndexOf('.');
        if (idx < 0) return pkg;
        // Skip generic top-level packages — keep going up until something distinct.
        String leaf = pkg.substring(idx + 1);
        if (Set.of("legacy", "atlas", "envestnet", "broadridge").contains(leaf)) {
            return "root";
        }
        return leaf;
    }

    /* ---------------- DTOs ---------------- */

    public static class Result {
        public List<Module> modules = new ArrayList<>();
        public int totalFindings;
    }

    public static class Module {
        public String name;
        public String packageName;
        public int fileCount;
        public int loc;
        public int difficulty;             // 0-10
        public List<Finding> findings = new ArrayList<>();
    }

    public static class Finding {
        public String ruleId;
        public String ruleLabel;
        public String severity;
        public String filePath;
        public Integer lineStart;
        public Integer lineEnd;
        public String snippet;
        public String suggestedRecipe;
        public int weight;
    }
}
