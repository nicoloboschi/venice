package com.linkedin.venice.controller;

import com.linkedin.venice.controller.kafka.TopicCreator;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixCachedMetadataRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import java.util.HashMap;
import java.util.Map;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManager;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.log4j.Logger;


/**
 * Helix Admin based on 0.6.5 APIs.
 */
public class VeniceHelixAdmin implements Admin {
    private final String controllerName;
    private final String zkConnString;
    private final Map<String, HelixManager> helixManagers = new HashMap<>();
    private static final Logger logger = Logger.getLogger(VeniceHelixAdmin.class.getName());
    private final HelixAdmin admin;
    private TopicCreator topicCreator;
    private final ZkClient zkClient;
    private final Map<String, HelixCachedMetadataRepository> repositories = new HashMap<>();
    private final Map<String, VeniceControllerClusterConfig> configs = new HashMap<>();

    public VeniceHelixAdmin(String controllerName, String zkConnString, String kafkaZkConnString) {
        /* Controller name can be generated from the hostname and
        VMID https://docs.oracle.com/javase/7/docs/api/java/rmi/dgc/VMID.html
        but taking this parameter from the user for now
         */
        this.controllerName = controllerName;
        this.zkConnString = zkConnString;
        this.topicCreator = new TopicCreator(kafkaZkConnString);
        admin = new ZKHelixAdmin(zkConnString);
        //There is no way to get he internal zkClient from HelixManager or HelixAdmin. So create a new one here.
        zkClient = new ZkClient(zkConnString, ZkClient.DEFAULT_SESSION_TIMEOUT, ZkClient.DEFAULT_CONNECTION_TIMEOUT);
    }

    @Override
    public synchronized void start(String clusterName, VeniceControllerClusterConfig config) {
        if (helixManagers.containsKey(clusterName)) {
            throw new VeniceException("Cluster " + clusterName + " already has a helix controller ");
        }
        //Simply validate cluster name here.
        clusterName = clusterName.trim();
        if (clusterName.startsWith("/") || clusterName.endsWith("/") || clusterName.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Invalid cluster name:" + clusterName);
        }
        configs.put(clusterName, config);
        createClusterIfRequired(clusterName);
        HelixManager helixManager = HelixControllerMain
            .startHelixController(zkConnString, clusterName, controllerName, HelixControllerMain.STANDALONE);
        helixManagers.put(clusterName, helixManager);
        HelixCachedMetadataRepository repository = new HelixCachedMetadataRepository(zkClient, clusterName);
        repository.start();
        repositories.put(clusterName, repository);
    }

    @Override
    public synchronized void addStore(String clusterName, String storeName, String owner) {
        HelixCachedMetadataRepository repository = repositories.get(clusterName);
        if (repository == null) {
            handleClusterDoseNotStart(clusterName);
        }
        if(repository.getStore(storeName)!=null){
            handleStoreAlreadExists(clusterName,storeName);
        }
        VeniceControllerClusterConfig config = configs.get(clusterName);
        Store store = new Store(storeName, owner, System.currentTimeMillis(), config.getPersistenceType(),
            config.getRoutingStrategy(), config.getReadStrategy(), config.getOfflinePushStrategy());
        repository.addStore(store);
    }

    @Override
    public synchronized  void addVersion(String clusterName, String storeName, int versionNumber) {
        VeniceControllerClusterConfig config = configs.get(clusterName);
        if(config == null){
            handleClusterDoseNotStart(clusterName);
        }
        this.addVersion(clusterName, storeName, versionNumber, config.getNumberOfPartition(), config.getReplicaFactor());
    }

    @Override
    public synchronized void addVersion(String clusterName, String storeName,int versionNumber, int numberOfPartition, int replicaFactor) {
        HelixCachedMetadataRepository repository = repositories.get(clusterName);
        if (repository == null) {
            handleClusterDoseNotStart(clusterName);
        }
        repository.lock();
        Version version = null;
        try {
            Store store = repository.getStore(storeName);
            if(store == null){
               handleStoreAlreadExists(clusterName,storeName);
            }
            if(store.containsVersion(versionNumber)){
                handleVersionAlreadyExists(storeName,versionNumber);
            }
            version = new Version(storeName,versionNumber,System.currentTimeMillis());
            store.addVersion(version);
            repository.updateStore(store);
            logger.info("Add version:"+version.getNumber()+" for store:" + storeName);
        } finally {
            repository.unLock();
        }

        addHelixResource(clusterName, version.kafkaTopicName(), numberOfPartition, replicaFactor,
            configs.get(clusterName).getKafkaReplicaFactor());
    }

    @Override
    public synchronized void incrementVersion(String clusterName, String storeName, int numberOfPartition,
        int replicaFactor) {
        HelixCachedMetadataRepository repository = repositories.get(clusterName);
        if (repository == null) {
            handleClusterDoseNotStart(clusterName);
        }

        repository.lock();
        Version version = null;
        try {
            Store store = repository.getStore(storeName);
            if(store == null){
                handleStoreAlreadExists(clusterName, storeName);
            }
            version = store.increaseVersion();
            repository.updateStore(store);
            logger.info("Add version:"+version.getNumber()+" for store:" + storeName);
        } finally {
            repository.unLock();
        }

        addHelixResource(clusterName, version.kafkaTopicName(), numberOfPartition, replicaFactor,
            configs.get(clusterName).getKafkaReplicaFactor());
    }

    @Override
    public void incrementVersion(String clusterName, String storeName) {
        VeniceControllerClusterConfig config = configs.get(clusterName);
        if (config == null) {
            handleClusterDoseNotStart(clusterName);
        }
        this.incrementVersion(clusterName, storeName, config.getNumberOfPartition(), config.getReplicaFactor());
    }

    @Override
    public void addHelixResource(String clusterName, String resourceName) {
        VeniceControllerClusterConfig config = configs.get(clusterName);
        if (config == null) {
            handleClusterDoseNotStart(clusterName);
        }
        this.addHelixResource(clusterName, resourceName, config.getNumberOfPartition(), config.getReplicaFactor(),
            config.getKafkaReplicaFactor());
    }

    @Override
    public synchronized void addHelixResource(String clusterName, String resourceName, int numberOfPartition,
        int replicaFactor, int kafkaReplicaFactor) {
        topicCreator.createTopic(resourceName, numberOfPartition, kafkaReplicaFactor);

        if (!admin.getResourcesInCluster(clusterName).contains(resourceName)) {
            admin.addResource(clusterName, resourceName, numberOfPartition,
                VeniceStateModel.PARTITION_ONLINE_OFFLINE_STATE_MODEL, IdealState.RebalanceMode.FULL_AUTO.toString());
            admin.rebalance(clusterName, resourceName, replicaFactor);
            logger.info("Added " + resourceName + " as a resource to cluster: " + clusterName);
        } else {
            handleResourceAlreadyExists(resourceName);
        }
    }

    @Override
    public synchronized void stop(String clusterName) {
        HelixManager helixManager = helixManagers.remove(clusterName);
        if(helixManager == null) {
            logger.info("Cluster " + clusterName + " does not start, skipping stop");
            return;
        }
        helixManager.disconnect();
        HelixCachedMetadataRepository repository = repositories.remove(clusterName);;
        repository.clear();
        configs.remove(clusterName);
    }

    private void createClusterIfRequired(String clusterName) {
        if(admin.getClusters().contains(clusterName)) {
            logger.info("Cluster  " + clusterName + " already exists. ");
            return;
        }

        boolean isClusterCreated = admin.addCluster(clusterName, false);
        if(isClusterCreated == false) {
            logger.info("Cluster  " + clusterName + " Creation returned false. ");
            return;
        }

        HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
                forCluster(clusterName).build();
        Map<String, String> helixClusterProperties = new HashMap<String, String>();
        helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
        admin.setConfig(configScope, helixClusterProperties);
        logger.info("Cluster  " + clusterName + "  Completed, auto join to true. ");

        admin.addStateModelDef(clusterName, VeniceStateModel.PARTITION_ONLINE_OFFLINE_STATE_MODEL,
            VeniceStateModel.getDefinition());
    }

    private void handleStoreAlreadExists(String clusterName, String storeName) {
        String errorMessage = "Store:" + storeName + " already exists. Can not add it to cluster:" + clusterName;
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void handleResourceAlreadyExists(String resourceName) {
        String errorMessage = "Resource:" + resourceName + " already exists, Can not add it to Helix.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void handleVersionAlreadyExists(String storeName, int version) {
        String errorMessage =
            "Version" + version + " already exists in Store:" + storeName + ". Can not add it to store.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void handleClusterDoseNotStart(String clusterName) {
        String errorMessage = "Cluster " + clusterName + " does not initialized. Can not add store to it.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }


}
