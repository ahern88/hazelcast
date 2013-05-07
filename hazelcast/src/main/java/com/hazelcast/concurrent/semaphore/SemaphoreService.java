/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.concurrent.semaphore;

import com.hazelcast.client.ClientCommandHandler;
import com.hazelcast.concurrent.semaphore.client.*;
import com.hazelcast.config.SemaphoreConfig;
import com.hazelcast.nio.protocol.Command;
import com.hazelcast.partition.MigrationEndpoint;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.spi.*;
import com.hazelcast.spi.impl.ResponseHandlerFactory;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ConstructorFunction;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @ali 1/21/13
 */
public class SemaphoreService implements ManagedService, MigrationAwareService, MembershipAwareService,
        RemoteService, ClientProtocolService, ClientAwareService {

    public static final String SERVICE_NAME = "hz:impl:semaphoreService";

    private final ConcurrentMap<String, Permit> permitMap = new ConcurrentHashMap<String, Permit>();

    private final NodeEngine nodeEngine;

    public SemaphoreService(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
    }

    private final ConstructorFunction<String, Permit> permitConstructor = new ConstructorFunction<String, Permit>() {
        public Permit createNew(String name) {
            SemaphoreConfig config = nodeEngine.getConfig().getSemaphoreConfig(name);
            int partitionId = nodeEngine.getPartitionService().getPartitionId(name);
            return new Permit(partitionId, new SemaphoreConfig(config));
        }
    };

    public Permit getOrCreatePermit(String name) {
        return ConcurrencyUtil.getOrPutIfAbsent(permitMap, name, permitConstructor);
    }

    public void init(NodeEngine nodeEngine, Properties properties) {
    }

    public void reset() {
        permitMap.clear();
    }

    public void shutdown() {
        permitMap.clear();
    }

    public void memberAdded(MembershipServiceEvent event) {
    }

    public void memberRemoved(MembershipServiceEvent event) {
        String caller = event.getMember().getUuid();
        onOwnerDisconnected(caller);
    }

    private void onOwnerDisconnected(final String caller) {
        for (String name: permitMap.keySet()){
            int partitionId = nodeEngine.getPartitionService().getPartitionId(name);
            PartitionInfo info = nodeEngine.getPartitionService().getPartitionInfo(partitionId);
            if (nodeEngine.getThisAddress().equals(info.getOwner())){
                Operation op = new SemaphoreDeadMemberOperation(name, caller).setPartitionId(partitionId)
                        .setResponseHandler(ResponseHandlerFactory.createEmptyResponseHandler())
                        .setService(this).setNodeEngine(nodeEngine).setServiceName(SERVICE_NAME);
                nodeEngine.getOperationService().executeOperation(op);
            }
        }
    }

    public String getServiceName() {
        return SERVICE_NAME;
    }

    public SemaphoreProxy createDistributedObject(Object objectId) {
        return new SemaphoreProxy((String)objectId, this, nodeEngine);
    }

    public SemaphoreProxy createDistributedObjectForClient(Object objectId) {
        return createDistributedObject(objectId);
    }

    public void destroyDistributedObject(Object objectId) {
        permitMap.remove(String.valueOf(objectId));
    }

    public void beforeMigration(PartitionMigrationEvent partitionMigrationEvent) {
    }

    public Operation prepareReplicationOperation(PartitionReplicationEvent event) {
        Map<String, Permit> migrationData = new HashMap<String, Permit>();
        for (Map.Entry<String, Permit> entry: permitMap.entrySet()){
            String name = entry.getKey();
            Permit permit = entry.getValue();
            if (permit.getPartitionId() == event.getPartitionId() && permit.getConfig().getTotalBackupCount() >= event.getReplicaIndex()){
                migrationData.put(name, permit);
            }
        }
        if (migrationData.isEmpty()){
            return null;
        }
        return new SemaphoreReplicationOperation(migrationData);
    }

    public void insertMigrationData(Map<String, Permit> migrationData){
        permitMap.putAll(migrationData);
    }

    public void commitMigration(PartitionMigrationEvent event) {
        if (event.getMigrationEndpoint() == MigrationEndpoint.SOURCE){
            clearMigrationData(event.getPartitionId());
        }
    }

    private void clearMigrationData(int partitionId){
        Iterator<Map.Entry<String, Permit>> iter = permitMap.entrySet().iterator();
        while (iter.hasNext()){
            Permit permit = iter.next().getValue();
            if (permit.getPartitionId() == partitionId){
                iter.remove();
            }
        }
    }

    public void rollbackMigration(PartitionMigrationEvent event) {
        if (event.getMigrationEndpoint() == MigrationEndpoint.DESTINATION) {
            clearMigrationData(event.getPartitionId());
        }
    }

    public void clearPartitionReplica(int partitionId) {
        clearMigrationData(partitionId);
    }

    public Map<Command, ClientCommandHandler> getCommandsAsMap() {
        Map<Command, ClientCommandHandler> map = new HashMap<Command, ClientCommandHandler>();
        map.put(Command.SEMACQUIRE, new AcquireHandler(this));
        map.put(Command.SEMAVAILABLEPERMITS, new AvailablePermitsHandler(this));
        map.put(Command.SEMDRAINPERMITS, new DrainPermitsHandler(this));
        map.put(Command.SEMINIT, new InitHandler(this));
        map.put(Command.SEMRELEASE, new ReleaseHandler(this));
        map.put(Command.SEMTRYACQUIRE, new TryAcquireHandler(this));
        return null;
    }

    public void clientDisconnected(String clientUuid) {
        onOwnerDisconnected(clientUuid);
    }
}
