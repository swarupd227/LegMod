package com.envestnet.atlas.arch.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Walks a Java source tree and pulls out the structural facts the Code
 * Archaeology agent needs:
 *   - SOAP service interfaces (methods on java.rmi.Remote-derived interfaces)
 *   - Axis stub classes and their type-mapping registrations
 *   - Custom adapter classes (date / enum / numeric)
 *
 * Pure deterministic AST walk. Narrative generation is a separate step.
 */
public class SourceWalker {

    public static class Operation {
        public String namespace = "";
        public String name = "";
        public String inputType = "";
        public String outputType = "";
        public String soapStyle = "DOCUMENT";
        public String soapUse = "LITERAL";
        public String sourceClass = "";
        public int[] sourceLines = new int[]{0, 0};
        public List<String> faultTypes = new ArrayList<>();
        public List<String> flags = new ArrayList<>();
    }

    public static class TypeMapping {
        public String javaType = "";
        public String qnameNamespace = "";
        public String qnameLocal = "";
        public String adapterFqn = "";
        public String fieldName;
        public String notes;
    }

    public static class Adapter {
        public String fqn = "";
        public String kind = "other";
        public String pattern;
        public int[] sourceLines = new int[]{0, 0};
    }

    public static class Result {
        public List<Operation> operations = new ArrayList<>();
        public List<TypeMapping> typeMappings = new ArrayList<>();
        public List<Adapter> adapters = new ArrayList<>();
        public Map<String, String> classToTypeMappings = new LinkedHashMap<>();
    }

    public Result walk(Path sourceRoot) throws IOException {
        Result r = new Result();
        if (!Files.isDirectory(sourceRoot)) return r;

        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java")).toList();
        }

        // First pass — find adapters and stubs, collect type mappings.
        List<CompilationUnit> units = new ArrayList<>();
        for (Path p : javaFiles) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(p);
            } catch (Exception e) {
                continue;
            }
            units.add(cu);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
                String fqn = qualifiedName(cu, decl);
                if (looksLikeAdapter(decl)) {
                    Adapter a = new Adapter();
                    a.fqn = fqn;
                    a.kind = adapterKind(decl);
                    a.pattern = inferPattern(decl);
                    a.sourceLines = lineRange(decl);
                    r.adapters.add(a);
                }
                if (extendsAxisStub(decl)) {
                    extractTypeMappings(decl, r);
                }
            });
        }

        // Second pass — service interfaces + their methods become Operations.
        // We accept either:
        //   (1) Java interfaces extending java.rmi.Remote  — the canonical
        //       Axis 1.x / JAX-RPC shape
        //   (2) Concrete classes that look like Axis service impls — methods
        //       throwing RemoteException, no Stub parent. This catches the
        //       *BindingImpl pattern that WSDL2Java emits when shipping the
        //       PortType interface as a separate generated artifact (e.g.
        //       the WS-I SCM samples). Without this, real-world Axis
        //       codebases that don't check in their generated interfaces
        //       yield zero operations.
        // A (namespace, name) seen-set dedupes the case where two impl
        // classes in the same package implement the same operation —
        // logically a single SOAP op even though there are multiple Java
        // method bodies. Without this we collide on the unique constraint
        // on operation(project_id, namespace, name).
        Set<String> seen = new HashSet<>();
        for (CompilationUnit cu : units) {
            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(d -> isRemoteInterface(d) || looksLikeAxisServiceImpl(d))
                    .forEach(typeDecl -> {
                        String fqn = qualifiedName(cu, typeDecl);
                        String ns = inferNamespace(fqn);
                        for (MethodDeclaration m : typeDecl.getMethods()) {
                            // For impl classes (case 2 above), only the
                            // methods that actually throw RemoteException
                            // qualify as SOAP operations; private helpers and
                            // setters get filtered out.
                            if (!typeDecl.isInterface() && !throwsRemoteException(m)) continue;
                            // Skip non-public methods either way.
                            if (!typeDecl.isInterface() && !m.isPublic()) continue;
                            String dedupKey = ns + "::" + m.getNameAsString();
                            if (!seen.add(dedupKey)) continue;
                            Operation op = new Operation();
                            op.namespace = ns;
                            op.name = m.getNameAsString();
                            op.inputType = m.getParameters().isEmpty()
                                    ? "void"
                                    : m.getParameter(0).getTypeAsString();
                            op.outputType = m.getTypeAsString();
                            op.sourceClass = fqn;
                            op.sourceLines = lineRange(m);
                            op.faultTypes = m.getThrownExceptions().stream()
                                    .map(t -> t.toString())
                                    .filter(t -> !t.equals("RemoteException")
                                              && !t.equals("java.rmi.RemoteException"))
                                    .toList();
                            attachFlags(op, r);
                            r.operations.add(op);
                        }
                    });
        }

        return r;
    }

    /**
     * True when {@code d} is a concrete class (not an interface, not an
     * abstract impl, not an Axis-generated client stub) that has at least
     * one public method throwing {@code RemoteException}. This is the
     * canonical shape of a WSDL2Java-generated {@code *BindingImpl} or a
     * hand-written Axis 1.x service implementation.
     */
    private boolean looksLikeAxisServiceImpl(ClassOrInterfaceDeclaration d) {
        if (d.isInterface()) return false;
        if (extendsAxisStub(d)) return false;  // client-side stub, not server impl
        // Heuristic: at least one public method throwing RemoteException.
        return d.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .anyMatch(this::throwsRemoteException);
    }

    private boolean throwsRemoteException(MethodDeclaration m) {
        return m.getThrownExceptions().stream()
                .map(t -> t.toString())
                .anyMatch(s -> s.equals("RemoteException")
                            || s.endsWith(".RemoteException"));
    }

    private void extractTypeMappings(ClassOrInterfaceDeclaration stub, Result r) {
        Map<String, String> constants = collectStringConstants(stub);
        // Collect class-to-QName from `tm.register(SomeClass.class, new QName(NS, "Local"), ...)`
        // and from individual `register(...)` invocations on TypeMapping objects.
        stub.findAll(MethodCallExpr.class).stream()
                .filter(call -> "register".equals(call.getNameAsString()))
                .filter(call -> call.getArguments().size() >= 2)
                .forEach(call -> {
                    String javaType = call.getArgument(0).toString();
                    if (javaType.endsWith(".class")) {
                        javaType = javaType.substring(0, javaType.length() - ".class".length());
                    }
                    QName qn = readQName(call.getArgument(1), constants);
                    if (qn == null) return;

                    TypeMapping m = new TypeMapping();
                    m.javaType = javaType;
                    m.qnameNamespace = qn.ns;
                    m.qnameLocal = qn.local;
                    if (call.getArguments().size() >= 3) {
                        // Adapter SerializerFactory hint
                        String factoryArg = call.getArgument(2).toString();
                        if (factoryArg.contains("DateAdapter")) {
                            m.adapterFqn = "BroadridgeDateAdapter";
                            m.notes = "uses date adapter";
                        }
                    }
                    r.typeMappings.add(m);
                    r.classToTypeMappings.put(javaType, qn.ns + ":" + qn.local);
                });
    }

    private void attachFlags(Operation op, Result r) {
        // If any of the type mappings touched by this operation reference an
        // adapter, surface that as a flag for the agent narrative.
        boolean hasAdapter = r.typeMappings.stream()
                .anyMatch(m -> m.adapterFqn != null && !m.adapterFqn.isBlank());
        if (hasAdapter) op.flags.add("custom_serializer");

        boolean dateAdapter = r.adapters.stream()
                .anyMatch(a -> "date".equals(a.kind));
        if (dateAdapter && op.inputType != null
                && (op.inputType.contains("Allocation") || op.inputType.contains("Request"))) {
            op.flags.add("non_iso_date_format");
        }
    }

    /* ---------- predicates ---------- */

    private boolean looksLikeAdapter(ClassOrInterfaceDeclaration d) {
        String n = d.getNameAsString();
        if (n.endsWith("Adapter") && !n.equals("XmlAdapter")) return true;
        return d.getMethods().stream()
                .anyMatch(m -> "marshal".equals(m.getNameAsString())
                        || "unmarshal".equals(m.getNameAsString())
                        || "serialize".equals(m.getNameAsString()));
    }

    private String adapterKind(ClassOrInterfaceDeclaration d) {
        String name = d.getNameAsString();
        if (name.toLowerCase().contains("date")) return "date";
        if (name.toLowerCase().contains("enum")) return "enum";
        if (name.toLowerCase().matches(".*(decimal|number|amount).*")) return "numeric";
        return "other";
    }

    private String inferPattern(ClassOrInterfaceDeclaration d) {
        return d.findAll(StringLiteralExpr.class).stream()
                .map(StringLiteralExpr::getValue)
                .filter(s -> s.matches(".*[Mdyhms/-].*") && s.length() <= 32
                        && (s.contains("/") || s.contains("-") || s.contains(":")))
                .findFirst().orElse(null);
    }

    private boolean extendsAxisStub(ClassOrInterfaceDeclaration d) {
        return d.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().equals("Stub")
                        || t.getNameWithScope().contains("axis.client.Stub"));
    }

    private boolean isRemoteInterface(ClassOrInterfaceDeclaration d) {
        if (!d.isInterface()) return false;
        return d.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().equals("Remote")
                        || t.getNameWithScope().endsWith(".Remote"));
    }

    /* ---------- helpers ---------- */

    private static String qualifiedName(CompilationUnit cu, ClassOrInterfaceDeclaration d) {
        String pkg = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");
        return pkg.isEmpty() ? d.getNameAsString() : pkg + "." + d.getNameAsString();
    }

    private static int[] lineRange(com.github.javaparser.ast.Node n) {
        int begin = n.getBegin().map(p -> p.line).orElse(0);
        int end = n.getEnd().map(p -> p.line).orElse(begin);
        return new int[]{begin, end};
    }

    private static String inferNamespace(String fqn) {
        // Known vendor packages get their canonical SOAP namespaces.
        String[] parts = fqn.split("\\.");
        for (String p : parts) {
            if (p.equalsIgnoreCase("broadridge")) return "http://broadridge.com/services/mf";
            if (p.equalsIgnoreCase("pershing"))   return "http://pershing.com/services";
            if (p.equalsIgnoreCase("lpl"))        return "http://lpl.com/services";
        }
        // General fallback: the standard Java-package-to-XML-namespace
        // convention (reverse the package). Without this, every
        // `org.apache.*` impl gets the same namespace and operation names
        // collide on the (project, namespace, name) unique key. The
        // reversal mirrors what JAX-WS / Axis WSDL2Java emit by default.
        //   org.apache.axis.wsi.scm.manufacturer.ManufacturerSoapBindingImpl
        // → http://manufacturer.scm.wsi.axis.apache.org/services
        int classNameIdx = parts.length - 1;  // drop the class itself
        if (classNameIdx < 1) return "http://envestnet.com/services";
        StringBuilder host = new StringBuilder();
        for (int i = classNameIdx - 1; i >= 0; i--) {
            if (host.length() > 0) host.append('.');
            host.append(parts[i]);
        }
        return "http://" + host + "/services";
    }

    private record QName(String ns, String local) {}

    private QName readQName(com.github.javaparser.ast.Node arg, Map<String, String> constants) {
        if (arg instanceof ObjectCreationExpr oce
                && "QName".equals(oce.getType().getNameAsString())
                && oce.getArguments().size() >= 2) {
            String ns = resolveLiteral(oce.getArgument(0), constants);
            String local = resolveLiteral(oce.getArgument(1), constants);
            if (ns != null && local != null) return new QName(ns, local);
        }
        return null;
    }

    private static String resolveLiteral(com.github.javaparser.ast.Node n,
                                         Map<String, String> constants) {
        if (n instanceof StringLiteralExpr s) return s.getValue();
        if (n instanceof NameExpr nm) {
            String resolved = constants.get(nm.getNameAsString());
            if (resolved != null) return resolved;
            return nm.getNameAsString();   // unresolved — keep symbolic
        }
        return null;
    }

    private static Map<String, String> collectStringConstants(ClassOrInterfaceDeclaration cls) {
        Map<String, String> out = new HashMap<>();
        cls.getFields().forEach(field -> {
            if (!field.isStatic() || !field.isFinal()) return;
            field.getVariables().forEach(v -> v.getInitializer().ifPresent(init -> {
                if (init instanceof StringLiteralExpr sl) {
                    out.put(v.getNameAsString(), sl.getValue());
                }
            }));
        });
        return out;
    }
}
