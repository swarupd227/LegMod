package com.envestnet.broadridge;

import javax.xml.namespace.QName;
import javax.xml.rpc.ServiceException;

public class AllocationServiceLocator extends org.apache.axis.client.Service
        implements javax.xml.rpc.Service {

    private static final String DEFAULT_PORT_ADDRESS =
        "http://broadridge.example.com/services/AllocationService";

    public AllocationService getAllocationServicePort() throws ServiceException {
        try {
            return new AllocationStub(new java.net.URL(DEFAULT_PORT_ADDRESS), this);
        } catch (java.net.MalformedURLException e) {
            throw new ServiceException(e);
        }
    }

    public QName getServiceName() {
        return new QName("http://broadridge.com/services/mf", "AllocationService");
    }
}
