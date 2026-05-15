package com.envestnet.broadridge;

import com.envestnet.broadridge.model.AllocationRequest;
import com.envestnet.broadridge.model.AllocationResponse;
import com.envestnet.broadridge.model.AllocationStatus;

import java.rmi.RemoteException;

/**
 * Original Broadridge Mutual Fund allocation service interface.
 * Generated against AllocationService.wsdl by the Axis 1.3 wsdl2java tool.
 */
public interface AllocationService extends java.rmi.Remote {

    AllocationResponse submitAllocation(AllocationRequest request) throws RemoteException;

    AllocationStatus getAllocationStatus(String allocationId) throws RemoteException;

    AllocationResponse[] batchAllocate(AllocationRequest[] requests) throws RemoteException;

    boolean cancelAllocation(String allocationId, String reason) throws RemoteException;
}
