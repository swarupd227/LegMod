package com.envestnet.broadridge;

import com.envestnet.broadridge.model.*;

import javax.xml.namespace.QName;
import javax.xml.rpc.ServiceException;
import javax.xml.rpc.encoding.TypeMapping;
import javax.xml.rpc.encoding.TypeMappingRegistry;
import java.rmi.RemoteException;

/**
 * Axis 1.3 generated stub — the canonical archaeological target for the
 * Code Archaeology agent. Real-world stubs are 1k+ lines; this is trimmed
 * to the patterns Atlas Migrate must recover:
 *   - register* invocations producing QName ↔ Java class mappings
 *   - a custom serializer factory referenced by tradeDate
 *   - operation methods that delegate to Axis Call objects
 */
public class AllocationStub extends org.apache.axis.client.Stub
        implements AllocationService {

    private static final String NS_BROADRIDGE = "http://broadridge.com/services/mf";
    private static final String NS_TYPES      = "http://broadridge.com/services/mf/types";

    static org.apache.axis.description.OperationDesc[] _operations;

    static {
        _operations = new org.apache.axis.description.OperationDesc[4];
        _initOperationDesc1();
    }

    private static void _initOperationDesc1() {
        org.apache.axis.description.OperationDesc oper;

        oper = new org.apache.axis.description.OperationDesc();
        oper.setName("submitAllocation");
        oper.addParameter(
            new QName(NS_BROADRIDGE, "request"),
            new QName(NS_TYPES, "AllocationRequest"),
            AllocationRequest.class,
            org.apache.axis.description.ParameterDesc.IN, false, false);
        oper.setReturnType(new QName(NS_TYPES, "AllocationResponse"));
        oper.setReturnClass(AllocationResponse.class);
        oper.setReturnQName(new QName(NS_BROADRIDGE, "submitAllocationReturn"));
        oper.setStyle(org.apache.axis.constants.Style.WRAPPED);
        oper.setUse(org.apache.axis.constants.Use.LITERAL);
        _operations[0] = oper;

        oper = new org.apache.axis.description.OperationDesc();
        oper.setName("getAllocationStatus");
        oper.addParameter(
            new QName(NS_BROADRIDGE, "allocationId"),
            new QName("http://www.w3.org/2001/XMLSchema", "string"),
            String.class,
            org.apache.axis.description.ParameterDesc.IN, false, false);
        oper.setReturnType(new QName(NS_TYPES, "AllocationStatus"));
        oper.setReturnClass(AllocationStatus.class);
        _operations[1] = oper;

        oper = new org.apache.axis.description.OperationDesc();
        oper.setName("batchAllocate");
        oper.addParameter(
            new QName(NS_BROADRIDGE, "requests"),
            new QName(NS_TYPES, "AllocationRequest"),
            AllocationRequest[].class,
            org.apache.axis.description.ParameterDesc.IN, false, false);
        oper.setReturnType(new QName(NS_TYPES, "AllocationResponse"));
        oper.setReturnClass(AllocationResponse[].class);
        _operations[2] = oper;

        oper = new org.apache.axis.description.OperationDesc();
        oper.setName("cancelAllocation");
        oper.addParameter(
            new QName(NS_BROADRIDGE, "allocationId"),
            new QName("http://www.w3.org/2001/XMLSchema", "string"),
            String.class, org.apache.axis.description.ParameterDesc.IN, false, false);
        oper.addParameter(
            new QName(NS_BROADRIDGE, "reason"),
            new QName("http://www.w3.org/2001/XMLSchema", "string"),
            String.class, org.apache.axis.description.ParameterDesc.IN, false, false);
        oper.setReturnType(new QName("http://www.w3.org/2001/XMLSchema", "boolean"));
        oper.setReturnClass(boolean.class);
        _operations[3] = oper;
    }

    public AllocationStub(java.net.URL endpointURL,
                          javax.xml.rpc.Service service) throws ServiceException {
        super(endpointURL, service);
        registerCustomTypes();
    }

    /**
     * The recoverable nugget: a custom DateAdapter is registered for the
     * tradeDate field, emitting MM/dd/yyyy strings rather than ISO 8601.
     * Atlas Migrate's archaeology agent must detect this and the Translation
     * agent must produce an equivalent JAXB XmlAdapter.
     */
    private void registerCustomTypes() {
        TypeMappingRegistry reg = service.getTypeMappingRegistry();
        TypeMapping tm = reg.getOrMakeTypeMapping(
            org.apache.axis.Constants.URI_DEFAULT_SOAP_ENC);

        tm.register(
            AllocationRequest.class,
            new QName(NS_TYPES, "AllocationRequest"),
            new BroadridgeDateAdapter.SerializerFactory(AllocationRequest.class),
            new BroadridgeDateAdapter.DeserializerFactory(AllocationRequest.class));

        tm.register(
            AllocationResponse.class,
            new QName(NS_TYPES, "AllocationResponse"),
            new org.apache.axis.encoding.ser.BeanSerializerFactory(
                AllocationResponse.class, new QName(NS_TYPES, "AllocationResponse")),
            new org.apache.axis.encoding.ser.BeanDeserializerFactory(
                AllocationResponse.class, new QName(NS_TYPES, "AllocationResponse")));

        tm.register(
            AllocationStatus.class,
            new QName(NS_TYPES, "AllocationStatus"),
            new org.apache.axis.encoding.ser.EnumSerializerFactory(
                AllocationStatus.class, new QName(NS_TYPES, "AllocationStatus")),
            new org.apache.axis.encoding.ser.EnumDeserializerFactory(
                AllocationStatus.class, new QName(NS_TYPES, "AllocationStatus")));
    }

    @Override
    public AllocationResponse submitAllocation(AllocationRequest request) throws RemoteException {
        if (super.cachedEndpoint == null) {
            throw new org.apache.axis.NoEndPointException();
        }
        org.apache.axis.client.Call _call = createCall();
        _call.setOperation(_operations[0]);
        _call.setUseSOAPAction(true);
        _call.setSOAPActionURI("submitAllocation");
        _call.setOperationName(new QName(NS_BROADRIDGE, "submitAllocation"));
        Object _resp = _call.invoke(new Object[]{request});
        return (AllocationResponse) _resp;
    }

    @Override
    public AllocationStatus getAllocationStatus(String allocationId) throws RemoteException {
        org.apache.axis.client.Call _call = createCall();
        _call.setOperation(_operations[1]);
        Object _resp = _call.invoke(new Object[]{allocationId});
        return (AllocationStatus) _resp;
    }

    @Override
    public AllocationResponse[] batchAllocate(AllocationRequest[] requests) throws RemoteException {
        org.apache.axis.client.Call _call = createCall();
        _call.setOperation(_operations[2]);
        Object _resp = _call.invoke(new Object[]{requests});
        return (AllocationResponse[]) _resp;
    }

    @Override
    public boolean cancelAllocation(String allocationId, String reason) throws RemoteException {
        org.apache.axis.client.Call _call = createCall();
        _call.setOperation(_operations[3]);
        Object _resp = _call.invoke(new Object[]{allocationId, reason});
        return (Boolean) _resp;
    }

    private org.apache.axis.client.Call createCall() {
        try {
            return (org.apache.axis.client.Call) super.service.createCall();
        } catch (ServiceException e) {
            throw new RuntimeException(e);
        }
    }
}
