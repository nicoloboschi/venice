package com.linkedin.venice.pushmonitor;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.OfflinePushStrategy;
import com.linkedin.venice.meta.ReadWriteStoreRepository;
import com.linkedin.venice.meta.RoutingDataRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreCleaner;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.replication.TopicReplicator;
import com.linkedin.venice.utils.TestUtils;
import io.tehuti.metrics.MetricsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


public abstract class AbstractPushMonitorTest {
  private OfflinePushAccessor mockAccessor;
  private AbstractPushMonitor monitor;
  private ReadWriteStoreRepository mockStoreRepo;
  private RoutingDataRepository mockRoutingDataRepo;
  private StoreCleaner mockStoreCleaner;
  private AggPushHealthStats mockPushHealthStats;
  private MetricsRepository metricsRepository;

  private String clusterName =TestUtils.getUniqueString("test_cluster");
  private String storeName;
  private String topic;

  private int numberOfPartition = 1;
  private int replicationFactor = 3;

  protected AbstractPushMonitor getPushMonitor() {
    return getPushMonitor(false, mock(TopicReplicator.class));
  }

  protected abstract AbstractPushMonitor getPushMonitor(boolean skipBufferReplayForHybrid, TopicReplicator mockReplicator);

  @BeforeMethod
  public void setup() {
    storeName = TestUtils.getUniqueString("test_store");
    topic = storeName + "_v1";

    mockAccessor = mock(OfflinePushAccessor.class);
    mockStoreCleaner = mock(StoreCleaner.class);
    mockStoreRepo = mock(ReadWriteStoreRepository.class);
    mockRoutingDataRepo = mock(RoutingDataRepository.class);
    mockPushHealthStats = mock(AggPushHealthStats.class);
    metricsRepository = new MetricsRepository();
    monitor = getPushMonitor();
  }

  @Test
  public void testStartMonitorOfflinePush() {
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    OfflinePushStatus pushStatus = monitor.getOfflinePush(topic);
    Assert.assertEquals(pushStatus.getCurrentStatus(), ExecutionStatus.STARTED);
    Assert.assertEquals(pushStatus.getKafkaTopic(), topic);
    Assert.assertEquals(pushStatus.getNumberOfPartition(), numberOfPartition);
    Assert.assertEquals(pushStatus.getReplicationFactor(), replicationFactor);
    verify(mockAccessor, atLeastOnce()).createOfflinePushStatusAndItsPartitionStatuses(pushStatus);
    verify(mockAccessor, atLeastOnce()).subscribePartitionStatusChange(pushStatus, monitor);
    verify(mockRoutingDataRepo, atLeastOnce()).subscribeRoutingDataChange(getTopic(), monitor);
    try {
      monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
          OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
      Assert.fail("Duplicated monitoring is not allowed. ");
    } catch (VeniceException e) {
    }
  }

  @Test
  public void testStartMonitorOfflinePushWhenThereIsAnExistingErrorPush() {
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    OfflinePushStatus pushStatus = monitor.getOfflinePush(topic);
    Assert.assertEquals(pushStatus.getCurrentStatus(), ExecutionStatus.STARTED);
    Assert.assertEquals(pushStatus.getKafkaTopic(), topic);
    Assert.assertEquals(pushStatus.getNumberOfPartition(), numberOfPartition);
    Assert.assertEquals(pushStatus.getReplicationFactor(), replicationFactor);
    verify(mockAccessor, atLeastOnce()).createOfflinePushStatusAndItsPartitionStatuses(pushStatus);
    verify(mockAccessor, atLeastOnce()).subscribePartitionStatusChange(pushStatus, monitor);
    verify(mockRoutingDataRepo, atLeastOnce()).subscribeRoutingDataChange(getTopic(), monitor);
    monitor.markOfflinePushAsError(topic, "mocked_error_push");
    Assert.assertEquals(monitor.getPushStatus(topic), ExecutionStatus.ERROR);

    // Existing error push will be cleaned up to start a new push
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
  }

  @Test
  public void testStopMonitorOfflinePush() {
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    OfflinePushStatus pushStatus = monitor.getOfflinePush(topic);
    monitor.stopMonitorOfflinePush(topic);
    verify(mockAccessor, atLeastOnce()).deleteOfflinePushStatusAndItsPartitionStatuses(pushStatus);
    verify(mockAccessor, atLeastOnce()).unsubscribePartitionsStatusChange(pushStatus, monitor);
    verify(mockRoutingDataRepo, atLeastOnce()).unSubscribeRoutingDataChange(getTopic(), monitor);

    try {
      monitor.getOfflinePush(topic);
      Assert.fail("Push status should be deleted by stopMonitorOfflinePush method");
    } catch (VeniceException e) {
    }
  }

  @Test
  public void testStopMonitorErrorOfflinePush() {
    String store = getStoreName();
    for (int i = 0; i < OfflinePushMonitor.MAX_PUSH_TO_KEEP; i++) {
      String topic = Version.composeKafkaTopic(store, i);
      monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
          OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
      OfflinePushStatus pushStatus = monitor.getOfflinePush(topic);
      pushStatus.updateStatus(ExecutionStatus.ERROR);
      monitor.stopMonitorOfflinePush(topic);
    }
    // We should keep MAX_ERROR_PUSH_TO_KEEP error push for debug.
    for (int i = 0; i < OfflinePushMonitor.MAX_PUSH_TO_KEEP; i++) {
      Assert.assertNotNull(monitor.getOfflinePush(Version.composeKafkaTopic(store, i)));
    }
    // Add a new error push, the oldest one should be collected.
    String topic = Version.composeKafkaTopic(store, OfflinePushMonitor.MAX_PUSH_TO_KEEP + 1);
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    OfflinePushStatus pushStatus = monitor.getOfflinePush(topic);
    pushStatus.updateStatus(ExecutionStatus.ERROR);
    monitor.stopMonitorOfflinePush(topic);
    try {
      monitor.getOfflinePush(Version.composeKafkaTopic(store, 0));
      Assert.fail("Oldest error push should be collected.");
    } catch (VeniceException e) {
      //expected
    }
    Assert.assertNotNull(monitor.getOfflinePush(topic));
  }

  @Test
  public void testLoadAllPushes() {
    int statusCount = 3;
    List<OfflinePushStatus> statusList = new ArrayList<>(statusCount);
    for (int i = 0; i < statusCount; i++) {
      OfflinePushStatus pushStatus =
          new OfflinePushStatus("testLoadAllPushes_v" + i, numberOfPartition, replicationFactor,
              OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
      pushStatus.setCurrentStatus(ExecutionStatus.COMPLETED);
      statusList.add(pushStatus);
    }
    doReturn(statusList).when(mockAccessor).loadOfflinePushStatusesAndPartitionStatuses();
    when(mockAccessor.getOfflinePushStatusAndItsPartitionStatuses(Mockito.anyString())).thenAnswer(invocation ->
    {
      String kafkaTopic = invocation.getArgument(0);
      for(OfflinePushStatus status : statusList) {
        if(status.getKafkaTopic().equals(kafkaTopic)) {
          return status;
        }
      }
      return null;
    });
    monitor.loadAllPushes();
    for (int i = 0; i < statusCount; i++) {
      Assert.assertEquals(monitor.getOfflinePush("testLoadAllPushes_v" + i).getCurrentStatus(),
          ExecutionStatus.COMPLETED);
    }
  }

  @Test
  public void testClearOldErrorVersion() {
    //creating MAX_PUSH_TO_KEEP * 2 pushes. The first is successful and the rest of them are failed.
    int statusCount = OfflinePushMonitor.MAX_PUSH_TO_KEEP * 2;
    List<OfflinePushStatus> statusList = new ArrayList<>(statusCount);
    for (int i = 0; i < statusCount; i++) {
      OfflinePushStatus pushStatus =
          new OfflinePushStatus("testLoadAllPushes_v" + i, numberOfPartition, replicationFactor,
              OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);

      //set all push statuses except the first to error
      if (i == 0) {
        pushStatus.setCurrentStatus(ExecutionStatus.COMPLETED);
      } else {
        pushStatus.setCurrentStatus(ExecutionStatus.ERROR);
      }
      statusList.add(pushStatus);
    }
    doReturn(statusList).when(mockAccessor).loadOfflinePushStatusesAndPartitionStatuses();

    when(mockAccessor.getOfflinePushStatusAndItsPartitionStatuses(Mockito.anyString())).thenAnswer(invocation ->
    {
      String kafkaTopic = invocation.getArgument(0);
      for(OfflinePushStatus status : statusList) {
        if(status.getKafkaTopic().equals(kafkaTopic)) {
          return status;
        }
      }
      return null;
    });

    monitor.loadAllPushes();
    // Make sure we delete old error pushes from accessor.
    verify(mockAccessor, times(statusCount - OfflinePushMonitor.MAX_PUSH_TO_KEEP))
        .deleteOfflinePushStatusAndItsPartitionStatuses(any());

    //the first push should be persisted since it succeeded. But the next 5 pushes should be purged.
    int i = 0;
    Assert.assertEquals(monitor.getPushStatus("testLoadAllPushes_v" + i), ExecutionStatus.COMPLETED);

    for (i = 1; i <= OfflinePushMonitor.MAX_PUSH_TO_KEEP; i++) {
      try {
        monitor.getOfflinePush("testLoadAllPushes_v" + i);
        Assert.fail("Old error pushes should be collected after loading.");
      } catch (VeniceException e) {
        //expected
      }
    }

    for (; i < statusCount; i++) {
      Assert.assertEquals(monitor.getPushStatus("testLoadAllPushes_v" + i), ExecutionStatus.ERROR);
    }
  }

  @Test
  public void testOnRoutingDataDeleted() {
    String topic = getTopic();
    prepareMockStore(topic);
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    // Resource has been deleted from the external view but still existing in the ideal state.
    doReturn(true).when(mockRoutingDataRepo).doseResourcesExistInIdealState(topic);
    monitor.onRoutingDataDeleted(topic);
    // Job should keep running.
    Assert.assertEquals(monitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.STARTED);

    // Resource has been deleted from both external view and ideal state.
    doReturn(false).when(mockRoutingDataRepo).doseResourcesExistInIdealState(topic);
    monitor.onRoutingDataDeleted(topic);
    // Job should be terminated in error status.
    Assert.assertEquals(monitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.ERROR);
    verify(mockPushHealthStats, times(1)).recordFailedPush(eq(getStoreName()), anyLong());
  }

  @Test
  public void testQueryingIncrementalPushJobStatus() {
    String topic = getTopic();
    String incrementalPushVersion = String.valueOf(System.currentTimeMillis());

    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);
    Assert.assertEquals(monitor.getPushStatus(topic, Optional.of(incrementalPushVersion)),
        ExecutionStatus.NOT_CREATED);

    //prepare new partition status
    List<ReplicaStatus> replicaStatuses = new ArrayList<>();
    for (int i = 0; i < replicationFactor; i++) {
      ReplicaStatus replicaStatus = new ReplicaStatus("test" + i);
      replicaStatuses.add(replicaStatus);
    }

    //update one of the replica status START -> COMPLETE -> START_OF_INCREMENTAL_PUSH_RECEIVED (SOIP_RECEIVED)
    replicaStatuses.get(0).updateStatus(ExecutionStatus.COMPLETED);
    replicaStatuses.get(0).updateStatus(ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);
    prepareMockStore(topic);

    monitor.onPartitionStatusChange(topic, new ReadOnlyPartitionStatus(0, replicaStatuses));
    //OfflinePushMonitor should return SOIP_RECEIVED if any of replica receives SOIP_RECEIVED
    Assert.assertEquals(monitor.getPushStatus(topic, Optional.of(incrementalPushVersion)),
        ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED);

    //update 2 of the replica status to END_OF_INCREMENTAL_PUSH_RECEIVED (EOIP_RECEIVED)
    //and update the third one to EOIP_RECEIVED with wrong version
    replicaStatuses.get(0).updateStatus(ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);

    replicaStatuses.get(1).updateStatus(ExecutionStatus.COMPLETED);
    replicaStatuses.get(1).updateStatus(ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);
    replicaStatuses.get(1).updateStatus(ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);

    replicaStatuses.get(2).updateStatus(ExecutionStatus.COMPLETED);
    replicaStatuses.get(2).updateStatus(ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);
    replicaStatuses.get(2).updateStatus(ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED, "incorrect_version");

    monitor.onPartitionStatusChange(topic, new ReadOnlyPartitionStatus(0, replicaStatuses));
    //OfflinePushMonitor should be able to filter out irrelevant IP versions
    Assert.assertEquals(monitor.getPushStatus(topic, Optional.of(incrementalPushVersion)),
        ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED);

    replicaStatuses.get(2).updateStatus(ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED, incrementalPushVersion);

    monitor.onPartitionStatusChange(topic, new ReadOnlyPartitionStatus(0, replicaStatuses));
    Assert.assertEquals(monitor.getPushStatus(topic, Optional.of(incrementalPushVersion)),
        ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED);

    replicaStatuses.get(0).updateStatus(ExecutionStatus.WARNING, incrementalPushVersion);

    monitor.onPartitionStatusChange(topic, new ReadOnlyPartitionStatus(0, replicaStatuses));
    Assert.assertEquals(monitor.getPushStatus(topic, Optional.of(incrementalPushVersion)),
        ExecutionStatus.ERROR);
  }

  @Test
  public void testDisableBufferReplayForHybrid() {
    String topic = "hybridTestStore_v1";
    // Prepare a hybrid store.
    Store store = prepareMockStore(topic);
    store.setHybridStoreConfig(new HybridStoreConfig(100, 100));
    // Prepare a mock topic replicator
    TopicReplicator mockReplicator = mock(TopicReplicator.class);
    AbstractPushMonitor testMonitor = getPushMonitor(true, mockReplicator);
    // Start a push
    testMonitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);

    // Prepare the new partition status
    List<ReplicaStatus> replicaStatuses = new ArrayList<>();
    for (int i = 0; i < replicationFactor; i++) {
      ReplicaStatus replicaStatus = new ReplicaStatus("test" + i);
      replicaStatuses.add(replicaStatus);
    }
    // All replicas are in STARTED status
    ReadOnlyPartitionStatus partitionStatus = new ReadOnlyPartitionStatus(0, replicaStatuses);

    // Check hybrid push status
    testMonitor.onPartitionStatusChange(topic, partitionStatus);
    // Not ready to send SOBR
    verify(mockReplicator, never()).prepareAndStartReplication(any(), any(), any());
    Assert.assertEquals(testMonitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.STARTED,
        "Hybrid push is not ready to send SOBR.");

    // One replica received end of push
    replicaStatuses.get(0).updateStatus(ExecutionStatus.END_OF_PUSH_RECEIVED);
    testMonitor.onPartitionStatusChange(topic, partitionStatus);
    // no buffer replay should be sent
    verify(mockReplicator, never())
        .prepareAndStartReplication(eq(Version.composeRealTimeTopic(store.getName())), eq(topic), eq(store));
    Assert.assertEquals(testMonitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.END_OF_PUSH_RECEIVED,
        "At least one replica already received end_of_push, so we send SOBR and update push status to END_OF_PUSH_RECEIVED");

    // Another replica received end of push
    replicaStatuses.get(1).updateStatus(ExecutionStatus.END_OF_PUSH_RECEIVED);
    mockReplicator = mock(TopicReplicator.class);
    testMonitor.setTopicReplicator(Optional.of(mockReplicator));
    testMonitor.onPartitionStatusChange(topic, partitionStatus);
    // Should not send SOBR again
    verify(mockReplicator, never()).prepareAndStartReplication(any(), any(), any());
  }

  @Test
  public void testOnPartitionStatusChangeForHybridStore() {
    String topic = getTopic();
    // Prepare a hybrid store.
    Store store = prepareMockStore(topic);
    store.setHybridStoreConfig(new HybridStoreConfig(100, 100));
    // Prepare a mock topic replicator
    TopicReplicator mockReplicator = mock(TopicReplicator.class);
    monitor.setTopicReplicator(Optional.of(mockReplicator));
    // Start a push
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);

    // Prepare the new partition status
    List<ReplicaStatus> replicaStatuses = new ArrayList<>();
    for (int i = 0; i < replicationFactor; i++) {
      ReplicaStatus replicaStatus = new ReplicaStatus("test" + i);
      replicaStatuses.add(replicaStatus);
    }
    // All replicas are in STARTED status
    ReadOnlyPartitionStatus partitionStatus = new ReadOnlyPartitionStatus(0, replicaStatuses);

    // Check hybrid push status
    monitor.onPartitionStatusChange(topic, partitionStatus);
    // Not ready to send SOBR
    verify(mockReplicator, never()).prepareAndStartReplication(any(), any(), any());
    Assert.assertEquals(monitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.STARTED,
        "Hybrid push is not ready to send SOBR.");

    // One replica received end of push
    replicaStatuses.get(0).updateStatus(ExecutionStatus.END_OF_PUSH_RECEIVED);
    monitor.onPartitionStatusChange(topic, partitionStatus);
    verify(mockReplicator,times(1))
        .prepareAndStartReplication(eq(Version.composeRealTimeTopic(store.getName())), eq(topic), eq(store));
    Assert.assertEquals(monitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.END_OF_PUSH_RECEIVED,
        "At least one replica already received end_of_push, so we send SOBR and update push status to END_OF_PUSH_RECEIVED");

    // Another replica received end of push
    replicaStatuses.get(1).updateStatus(ExecutionStatus.END_OF_PUSH_RECEIVED);
    mockReplicator = mock(TopicReplicator.class);
    monitor.setTopicReplicator(Optional.of(mockReplicator));
    monitor.onPartitionStatusChange(topic, partitionStatus);
    // Should not send SOBR again
    verify(mockReplicator, never()).prepareAndStartReplication(any(), any(), any());
  }

  @Test
  public void testOnPartitionStatusChangeForHybridStoreParallel()
      throws InterruptedException {
    String topic = getTopic();
    // Prepare a hybrid store.
    Store store = prepareMockStore(topic);
    store.setHybridStoreConfig(new HybridStoreConfig(100, 100));
    // Prepare a mock topic replicator
    TopicReplicator mockReplicator = mock(TopicReplicator.class);
    monitor.setTopicReplicator(Optional.of(mockReplicator));
    // Start a push
    monitor.startMonitorOfflinePush(topic, numberOfPartition, replicationFactor,
        OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION);

    int threadCount = 8;
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      Thread t = new Thread(() -> {
        List<ReplicaStatus> replicaStatuses = new ArrayList<>();
        for (int r = 0; r < replicationFactor; r++) {
          ReplicaStatus replicaStatus = new ReplicaStatus("test" + r);
          replicaStatus.updateStatus(ExecutionStatus.END_OF_PUSH_RECEIVED);
          replicaStatuses.add(replicaStatus);
        }
        // All replicas are in END_OF_PUSH_RECEIVED status
        ReadOnlyPartitionStatus partitionStatus = new ReadOnlyPartitionStatus(0, replicaStatuses);
        // Check hybrid push status
        monitor.onPartitionStatusChange(topic, partitionStatus);
      });
      threads[i] = t;
      t.start();
    }
    // After all thread was completely executed.
    for (int i = 0; i < threadCount; i++) {
      threads[i].join();
    }
    // Only send one SOBR
    verify(mockReplicator, only())
        .prepareAndStartReplication(eq(Version.composeRealTimeTopic(store.getName())), eq(topic), eq(store));
    Assert.assertEquals(monitor.getOfflinePush(topic).getCurrentStatus(), ExecutionStatus.END_OF_PUSH_RECEIVED,
        "At least one replica already received end_of_push, so we send SOBR and update push status to END_OF_PUSH_RECEIVED");
  }



  protected Store prepareMockStore(String topic) {
    String storeName = Version.parseStoreFromKafkaTopicName(topic);
    int versionNumber = Version.parseVersionFromKafkaTopicName(topic);
    Store store = TestUtils.createTestStore(storeName, "test", System.currentTimeMillis());
    Version version = new Version(storeName, versionNumber);
    version.setStatus(VersionStatus.STARTED);
    store.addVersion(version);
    doReturn(store).when(mockStoreRepo).getStore(storeName);
    return store;
  }

  protected OfflinePushAccessor getMockAccessor() {
    return mockAccessor;
  }

  protected AbstractPushMonitor getMonitor() {
    return monitor;
  }

  protected ReadWriteStoreRepository getMockStoreRepo() {
    return mockStoreRepo;
  }

  protected RoutingDataRepository getMockRoutingDataRepo() {
    return mockRoutingDataRepo;
  }

  protected StoreCleaner getMockStoreCleaner() {
    return mockStoreCleaner;
  }

  protected AggPushHealthStats getMockPushHealthStats() {
    return mockPushHealthStats;
  }

  protected MetricsRepository getMetricsRepository() { return metricsRepository; }

  protected String getStoreName() {
    return storeName;
  }

  protected String getClusterName() {
    return clusterName;
  }
  protected String getTopic() {
    return topic;
  }

  protected int getNumberOfPartition() {
    return numberOfPartition;
  }

  protected int getReplicationFactor() {
    return replicationFactor;
  }
}
