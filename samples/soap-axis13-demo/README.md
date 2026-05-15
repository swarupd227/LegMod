# soap-axis13-demo — Broadridge Mutual Fund Allocation (Axis 1.3)

Tiny but realistic Apache Axis 1.3 SOAP service used as the input fixture for
the SOAP migration track. Models the kind of legacy patterns the Code
Archaeology agent must recover:

- An Axis-style stub class with `TypeMappingRegistry` calls
- A custom `BroadridgeDateAdapter` that emits `MM/dd/yyyy` (not ISO 8601)
- A handful of operations with custom QName registrations
- A WSDL fragment, a service descriptor, and a sample request/response

This is **deliberately not buildable end-to-end** — Axis 1.3 is no longer
distributable cleanly and we do not need it to compile. Atlas Migrate reads it
as source. If you later want a buildable demo, drop in axis-1.4 jars from a
local archive.

## Layout

```
src/main/java/com/envestnet/broadridge/
  AllocationService.java         # service interface
  AllocationStub.java            # Axis-generated style stub with type mappings
  AllocationServiceLocator.java  # service locator
  BroadridgeDateAdapter.java     # custom MM/dd/yyyy adapter
  model/
    AllocationRequest.java
    AllocationResponse.java
    AllocationStatus.java
    Money.java
src/main/resources/
  AllocationService.wsdl         # original (incomplete / wire-derived) WSDL
  sample-request.xml             # one captured request envelope
  sample-response.xml            # corresponding response envelope
```
