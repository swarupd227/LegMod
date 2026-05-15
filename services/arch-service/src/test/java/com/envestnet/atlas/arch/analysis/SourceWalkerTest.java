package com.envestnet.atlas.arch.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceWalkerTest {

    private final SourceWalker walker = new SourceWalker();

    @Test
    void emptyTreeYieldsEmptyResult(@TempDir Path src) throws IOException {
        var r = walker.walk(src);
        assertThat(r.operations).isEmpty();
        assertThat(r.adapters).isEmpty();
        assertThat(r.typeMappings).isEmpty();
    }

    @Test
    void detectsServiceInterfaceAndExtractsOperations(@TempDir Path src) throws IOException {
        write(src, "com/example/svc/OrderService.java", """
                package com.example.svc;

                import java.rmi.Remote;
                import java.rmi.RemoteException;

                public interface OrderService extends Remote {
                    AllocationResponse submitAllocation(AllocationRequest req)
                            throws RemoteException, AllocationFault;
                    StatusResponse getStatus(StatusRequest req) throws RemoteException;
                }
                """);

        var r = walker.walk(src);

        assertThat(r.operations).hasSize(2);
        var submit = r.operations.stream()
                .filter(o -> "submitAllocation".equals(o.name)).findFirst().orElseThrow();
        assertThat(submit.inputType).isEqualTo("AllocationRequest");
        assertThat(submit.outputType).isEqualTo("AllocationResponse");
        // RemoteException is filtered out; AllocationFault remains.
        assertThat(submit.faultTypes).containsExactly("AllocationFault");
        // Default style/use:
        assertThat(submit.soapStyle).isEqualTo("DOCUMENT");
        assertThat(submit.soapUse).isEqualTo("LITERAL");
        // Source class is package-qualified.
        assertThat(submit.sourceClass).isEqualTo("com.example.svc.OrderService");
        // Source line range was captured.
        assertThat(submit.sourceLines[0]).isPositive();
    }

    @Test
    void parameterlessMethodsRecordVoidInput(@TempDir Path src) throws IOException {
        write(src, "com/example/svc/PingService.java", """
                package com.example.svc;
                import java.rmi.Remote;
                import java.rmi.RemoteException;
                public interface PingService extends Remote {
                    String ping() throws RemoteException;
                }
                """);

        var r = walker.walk(src);

        assertThat(r.operations).hasSize(1);
        assertThat(r.operations.get(0).inputType).isEqualTo("void");
    }

    @Test
    void detectsAdaptersByNameAndKindClassification(@TempDir Path src) throws IOException {
        write(src, "com/example/svc/BroadridgeDateAdapter.java", """
                package com.example.svc;
                public class BroadridgeDateAdapter {
                    public static final String FORMAT = "MM/dd/yyyy";
                    public String marshal(Object d) { return null; }
                    public Object unmarshal(String s) { return null; }
                }
                """);
        write(src, "com/example/svc/AccountTypeEnumAdapter.java", """
                package com.example.svc;
                public class AccountTypeEnumAdapter {
                    public Object marshal(Object x) { return null; }
                }
                """);
        write(src, "com/example/svc/DecimalAmountAdapter.java", """
                package com.example.svc;
                public class DecimalAmountAdapter {
                    public Object serialize(Object x) { return null; }
                }
                """);

        var r = walker.walk(src);

        assertThat(r.adapters).extracting(a -> a.kind)
                .containsExactlyInAnyOrder("date", "enum", "numeric");
        var date = r.adapters.stream()
                .filter(a -> "date".equals(a.kind)).findFirst().orElseThrow();
        assertThat(date.fqn).endsWith("BroadridgeDateAdapter");
        assertThat(date.pattern).isEqualTo("MM/dd/yyyy");
    }

    @Test
    void doesNotMisclassifyXmlAdapterAsAdapter(@TempDir Path src) throws IOException {
        // The walker explicitly skips classes literally named XmlAdapter
        // (the JAX-B base class). A class with no marshal/unmarshal/serialize
        // methods AND not ending in "Adapter" should be ignored.
        write(src, "com/example/svc/XmlAdapter.java", """
                package com.example.svc;
                public abstract class XmlAdapter<X, Y> {}
                """);
        write(src, "com/example/svc/PlainPojo.java", """
                package com.example.svc;
                public class PlainPojo {}
                """);

        var r = walker.walk(src);
        assertThat(r.adapters).isEmpty();
    }

    @Test
    void extractsAxisStubTypeMappingsAndLinksDateAdapter(@TempDir Path src) throws IOException {
        // A typical Axis 1.x stub exposes a private method that registers
        // type mappings on its TypeMappingRegistry. We need to recover
        // the (Java class) ↔ (QName ns, local) tuples.
        write(src, "com/example/svc/OrderServiceStub.java", """
                package com.example.svc;

                import org.apache.axis.client.Stub;
                import javax.xml.namespace.QName;

                public class OrderServiceStub extends Stub {
                    private static final String NS = "http://example.com/svc";

                    public OrderServiceStub() {
                        // tm is a TypeMapping instance; what matters is the call shape.
                        register(AllocationRequest.class,
                                 new QName(NS, "AllocationRequest"),
                                 new BroadridgeDateAdapter());
                        register(StatusResponse.class,
                                 new QName(NS, "StatusResponse"));
                    }
                    private void register(Class<?> c, QName q) {}
                    private void register(Class<?> c, QName q, Object f) {}
                }
                """);
        // Provide a date adapter so the operation flag attaches.
        write(src, "com/example/svc/BroadridgeDateAdapter.java", """
                package com.example.svc;
                public class BroadridgeDateAdapter {
                    public Object marshal(Object o) { return null; }
                }
                """);
        // Also a service interface that consumes AllocationRequest, so that
        // attachFlags() picks up the non_iso_date_format hint.
        write(src, "com/example/svc/OrderService.java", """
                package com.example.svc;
                import java.rmi.Remote;
                import java.rmi.RemoteException;
                public interface OrderService extends Remote {
                    Object submitAllocation(AllocationRequest req) throws RemoteException;
                }
                """);

        var r = walker.walk(src);

        assertThat(r.typeMappings).hasSize(2);
        var alloc = r.typeMappings.stream()
                .filter(m -> "AllocationRequest".equals(m.qnameLocal)).findFirst().orElseThrow();
        assertThat(alloc.qnameNamespace).isEqualTo("http://example.com/svc");
        assertThat(alloc.javaType).isEqualTo("AllocationRequest");
        assertThat(alloc.adapterFqn).isEqualTo("BroadridgeDateAdapter");

        // The submitAllocation operation should have flags surfaced.
        var op = r.operations.stream()
                .filter(o -> "submitAllocation".equals(o.name)).findFirst().orElseThrow();
        assertThat(op.flags).contains("custom_serializer", "non_iso_date_format");
    }

    @Test
    void inferNamespaceMatchesKnownVendorTokens(@TempDir Path src) throws IOException {
        // Only the package matters here; method body unused.
        write(src, "com/broadridge/services/Foo.java", """
                package com.broadridge.services;
                import java.rmi.Remote;
                import java.rmi.RemoteException;
                public interface Foo extends Remote {
                    String hello() throws RemoteException;
                }
                """);

        var r = walker.walk(src);
        assertThat(r.operations.get(0).namespace)
                .isEqualTo("http://broadridge.com/services/mf");
    }

    @Test
    void unparseableJavaFilesAreSkippedRatherThanFailing(@TempDir Path src) throws IOException {
        // A genuinely-broken .java file should not crash the walk.
        write(src, "com/example/svc/Broken.java",
                "this is { not valid Java )))))");
        // A valid one alongside it is still picked up.
        write(src, "com/example/svc/OrderService.java", """
                package com.example.svc;
                import java.rmi.Remote;
                import java.rmi.RemoteException;
                public interface OrderService extends Remote {
                    String ping() throws RemoteException;
                }
                """);

        var r = walker.walk(src);
        assertThat(r.operations).hasSize(1);
    }

    /* ---------------- helpers ---------------- */

    private static void write(Path root, String relPath, String contents) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, contents, StandardCharsets.UTF_8);
    }
}
