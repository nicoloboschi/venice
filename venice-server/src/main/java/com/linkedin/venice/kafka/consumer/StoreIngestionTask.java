package com.linkedin.venice.kafka.consumer;

import com.linkedin.venice.config.VeniceStoreConfig;
import com.linkedin.venice.exceptions.PersistenceFailureException;
import com.linkedin.venice.exceptions.UnsubscribedTopicPartitionException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceIngestionTaskKilledException;
import com.linkedin.venice.exceptions.VeniceMessageException;
import com.linkedin.venice.exceptions.VeniceInconsistentStoreMetadataException;
import com.linkedin.venice.exceptions.validation.UnsupportedMessageTypeException;
import com.linkedin.venice.guid.GuidUtils;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.kafka.protocol.ControlMessage;
import com.linkedin.venice.exceptions.validation.DuplicateDataException;
import com.linkedin.venice.exceptions.validation.FatalDataValidationException;
import com.linkedin.venice.kafka.protocol.EndOfIncrementalPush;
import com.linkedin.venice.kafka.protocol.StartOfBufferReplay;
import com.linkedin.venice.kafka.protocol.StartOfIncrementalPush;
import com.linkedin.venice.kafka.protocol.StartOfPush;
import com.linkedin.venice.kafka.protocol.state.IncrementalPush;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.kafka.validation.OffsetRecordTransformer;
import com.linkedin.venice.kafka.validation.ProducerTracker;
import com.linkedin.venice.kafka.protocol.GUID;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.ControlMessageType;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.notifier.VeniceNotifier;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.server.StoreRepository;
import com.linkedin.venice.stats.AggStoreIngestionStats;
import com.linkedin.venice.stats.AggVersionedDIVStats;
import com.linkedin.venice.storage.StorageMetadataService;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.store.StoragePartitionConfig;
import com.linkedin.venice.throttle.EventThrottler;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.DiskUsage;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.validation.constraints.NotNull;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.log4j.Logger;

import static com.linkedin.venice.stats.StatsErrorCode.*;
import static java.util.concurrent.TimeUnit.*;


/**
 * Assumes: One to One mapping between a Venice Store and Kafka Topic.
 * A runnable Kafka Consumer consuming messages from all the partition assigned to current node for a Kafka Topic.
 */
public class StoreIngestionTask implements Runnable, Closeable {

  // TOOD: Make this logger prefix everything with the CONSUMER_TASK_ID_FORMAT
  private static final Logger logger = Logger.getLogger(StoreIngestionTask.class);

  // Constants

  private static final String CONSUMER_TASK_ID_FORMAT = StoreIngestionTask.class.getSimpleName() + " for [ Topic: %s ]";
  public static long POLLING_SCHEMA_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
  /** After processing the following number of messages, Venice SN will report progress metrics. */
  public static int OFFSET_REPORTING_INTERVAL = 1000;
  private static final int MAX_CONTROL_MESSAGE_RETRIES = 3;
  private static final int MAX_IDLE_COUNTER  = 100;
  private static final int CONSUMER_ACTION_QUEUE_INIT_CAPACITY = 11;
  private static final long KILL_WAIT_TIME_MS = 5000l;
  private static final int MAX_KILL_CHECKING_ATTEMPTS = 10;
  private static final int SLOPPY_OFFSET_CATCHUP_THRESHOLD = 100;

  // Final stuff
  /** storage destination for consumption */
  private final StoreRepository storeRepository;
  private final String topic;
  private final String storeNameWithoutVersionInfo;
  private final int storeVersion;
  private final ReadOnlySchemaRepository schemaRepo;
  private final String consumerTaskId;
  private final Properties kafkaProps;
  private final VeniceConsumerFactory factory;
  private final AtomicBoolean isRunning;
  private final AtomicBoolean emitMetrics; //TODO: remove this once we migrate to versioned stats
  private final PriorityBlockingQueue<ConsumerAction> consumerActionsQueue;
  private final StorageMetadataService storageMetadataService;
  private final TopicManager topicManager;
  private final CachedLatestOffsetGetter cachedLatestOffsetGetter;
  private final EventThrottler bandwidthThrottler;
  private final EventThrottler recordsThrottler;
  /** Per-partition consumption state map */
  private final ConcurrentMap<Integer, PartitionConsumptionState> partitionConsumptionStateMap;
  private final StoreBufferService storeBufferService;
  /** Persists the exception thrown by {@link StoreBufferService}. */
  private Exception lastDrainerException = null;
  /** Keeps track of every upstream producer this consumer task has seen so far. */
  private final Map<GUID, ProducerTracker> producerTrackerMap;
  private final AggStoreIngestionStats storeIngestionStats;
  private final AggVersionedDIVStats versionedDIVStats;
  private final BooleanSupplier isCurrentVersion;
  private final Optional<HybridStoreConfig> hybridStoreConfig;
  private final IngestionNotificationDispatcher notificationDispatcher;
  private final Optional<ProducerTracker.DIVErrorMetricCallback> divErrorMetricCallback;

  private final Function<GUID, ProducerTracker> producerTrackerCreator;
  private final long readCycleDelayMs;
  private final long emptyPollSleepMs;

  private final DiskUsage diskUsage;

  // Non-final
  private KafkaConsumerWrapper consumer;
  private Set<Integer> schemaIdSet;
  private int idleCounter = 0;

  //this indicates whether it polls nothing from Kafka
  //It's for stats measuring purpose
  private boolean recordsPolled = true;

  /** Message bytes consuming interval before persisting offset in offset db for transactional mode database. */
  private final long databaseSyncBytesIntervalForTransactionalMode;
  /** Message bytes consuming interval before persisting offset in offset db for deferred-write database. */
  private final long databaseSyncBytesIntervalForDeferredWriteMode;

  /** A quick check point to see if incremental push is supported.
   * It helps fast {@link #isReadyToServe(PartitionConsumptionState)}*/
  private final boolean isIncrementalPushEnabled;

  private final boolean readOnlyForBatchOnlyStoreEnabled;

  /**
   * When buffer replay is disabled, {@link #measureHybridOffsetLag(StartOfBufferReplay, PartitionConsumptionState, boolean)}
   * won't consider the real-time topic offset.
   */
  private final boolean bufferReplayEnabledForHybrid;

  public StoreIngestionTask(@NotNull VeniceConsumerFactory factory,
                            @NotNull Properties kafkaConsumerProperties,
                            @NotNull StoreRepository storeRepository,
                            @NotNull StorageMetadataService storageMetadataService,
                            @NotNull Queue<VeniceNotifier> notifiers,
                            @NotNull EventThrottler bandwidthThrottler,
                            @NotNull EventThrottler recordsThrottler,
                            @NotNull ReadOnlySchemaRepository schemaRepo,
                            @NotNull TopicManager topicManager,
                            @NotNull AggStoreIngestionStats storeIngestionStats,
                            @NotNull AggVersionedDIVStats versionedDIVStats,
                            @NotNull StoreBufferService storeBufferService,
                            @NotNull BooleanSupplier isCurrentVersion,
                            @NotNull Optional<HybridStoreConfig> hybridStoreConfig,
                            @NotNull boolean isIncrementalPushEnabled,
                            @NotNull VeniceStoreConfig storeConfig,
                            DiskUsage diskUsage,
                            boolean bufferReplayEnabledForHybrid) {
    this.readCycleDelayMs = storeConfig.getKafkaReadCycleDelayMs();
    this.emptyPollSleepMs = storeConfig.getKafkaEmptyPollSleepMs();
    this.databaseSyncBytesIntervalForTransactionalMode = storeConfig.getDatabaseSyncBytesIntervalForTransactionalMode();
    this.databaseSyncBytesIntervalForDeferredWriteMode = storeConfig.getDatabaseSyncBytesIntervalForDeferredWriteMode();
    this.factory = factory;
    this.kafkaProps = kafkaConsumerProperties;
    this.storeRepository = storeRepository;
    this.storageMetadataService = storageMetadataService;
    this.bandwidthThrottler = bandwidthThrottler;
    this.recordsThrottler = recordsThrottler;
    this.topic = storeConfig.getStoreName();
    this.schemaRepo = schemaRepo;
    this.storeNameWithoutVersionInfo = Version.parseStoreFromKafkaTopicName(topic);
    this.storeVersion = Version.parseVersionFromKafkaTopicName(topic);
    this.schemaIdSet = new HashSet<>();
    this.consumerActionsQueue = new PriorityBlockingQueue<>(CONSUMER_ACTION_QUEUE_INIT_CAPACITY,
        new ConsumerAction.ConsumerActionPriorityComparator());

    // partitionConsumptionStateMap could be accessed by multiple threads: consumption thread and the thread handling kill message
    this.partitionConsumptionStateMap = new VeniceConcurrentHashMap<>();

    // Could be accessed from multiple threads since there are multiple worker threads.
    this.producerTrackerMap = new VeniceConcurrentHashMap<>();
    this.consumerTaskId = String.format(CONSUMER_TASK_ID_FORMAT, topic);
    this.topicManager = topicManager;
    this.cachedLatestOffsetGetter = new CachedLatestOffsetGetter(topicManager, storeConfig.getTopicOffsetCheckIntervalMs());

    this.storeIngestionStats = storeIngestionStats;
    this.versionedDIVStats = versionedDIVStats;

    this.isRunning = new AtomicBoolean(true);
    this.emitMetrics = new AtomicBoolean(true);

    this.storeBufferService = storeBufferService;
    this.isCurrentVersion = isCurrentVersion;
    this.hybridStoreConfig = hybridStoreConfig;
    this.isIncrementalPushEnabled = isIncrementalPushEnabled;
    this.notificationDispatcher = new IngestionNotificationDispatcher(notifiers, topic, isCurrentVersion);

    this.divErrorMetricCallback = Optional.of(e -> versionedDIVStats.recordException(storeNameWithoutVersionInfo, storeVersion, e));
    this.producerTrackerCreator = guid -> new ProducerTracker(guid, topic);

    this.diskUsage = diskUsage;

    this.readOnlyForBatchOnlyStoreEnabled = storeConfig.isReadOnlyForBatchOnlyStoreEnabled();

    this.bufferReplayEnabledForHybrid = bufferReplayEnabledForHybrid;
  }

  private void validateState() {
    if(!this.isRunning.get()) {
      throw new VeniceException(" Topic " + topic + " is shutting down, no more messages accepted");
    }
  }

  /**
   * Adds an asynchronous partition subscription request for the task.
   */
  public synchronized void subscribePartition(String topic, int partition) {
    validateState();
    consumerActionsQueue.add(new ConsumerAction(ConsumerActionType.SUBSCRIBE, topic, partition));
  }

  /**
   * Adds an asynchronous partition unsubscription request for the task.
   */
  public synchronized void unSubscribePartition(String topic, int partition) {
    validateState();
    consumerActionsQueue.add(new ConsumerAction(ConsumerActionType.UNSUBSCRIBE, topic, partition));
  }

  /**
   * Adds an asynchronous resetting partition consumption offset request for the task.
   */
  public synchronized void resetPartitionConsumptionOffset(String topic, int partition) {
    validateState();
    consumerActionsQueue.add(new ConsumerAction(ConsumerActionType.RESET_OFFSET, topic, partition));
  }

  public synchronized void kill() {
    validateState();
    consumerActionsQueue.add(ConsumerAction.createKillAction(topic));
    int currentAttempts = 0;
    try {
      // Check whether the task is really killed
      while (isRunning() && currentAttempts < MAX_KILL_CHECKING_ATTEMPTS) {
        MILLISECONDS.sleep(KILL_WAIT_TIME_MS / MAX_KILL_CHECKING_ATTEMPTS);
        currentAttempts ++;
      }
    } catch (InterruptedException e) {
      logger.warn("Wait killing is interrupted.", e);
    }
    if (isRunning()) {
      //If task is still running, force close it.
      notificationDispatcher.reportError(partitionConsumptionStateMap.values(), "Received the signal to kill this consumer. Topic " + topic,
          new VeniceException("Kill the consumer"));
      // close can not stop the consumption synchronizely, but the status of helix would be set to ERROR after
      // reportError. The only way to stop it synchronizely is interrupt the current running thread, but it's an unsafe
      // operation, for example it could break the ongoing db operation, so we should avoid that.
      this.close();
    }
  }

  private void beginBatchWrite(int partitionId, boolean sorted, PartitionConsumptionState partitionConsumptionState) {
    Map<String, String> checkpointedDatabaseInfo = partitionConsumptionState.getOffsetRecord().getDatabaseInfo();
    StoragePartitionConfig storagePartitionConfig = getStoragePartitionConfig(partitionId, sorted, partitionConsumptionState);
    partitionConsumptionState.setDeferredWrite(storagePartitionConfig.isDeferredWrite());
    storeRepository.getLocalStorageEngine(topic).beginBatchWrite(storagePartitionConfig, checkpointedDatabaseInfo);
    logger.info("Started batch write to store: " + topic + ", partition: " + partitionId +
        " with checkpointed database info: " + checkpointedDatabaseInfo + " and sorted: " + sorted);
  }

  private StoragePartitionConfig getStoragePartitionConfig(int partitionId, boolean sorted,
      PartitionConsumptionState partitionConsumptionState) {
    StoragePartitionConfig storagePartitionConfig = new StoragePartitionConfig(topic, partitionId);
    boolean deferredWrites;
    boolean readOnly = false;
    if (partitionConsumptionState.isEndOfPushReceived()) {
      // After EOP, we never enable deferred writes.
      // No matter what the sorted config was before the EOP message, it doesn't matter anymore.
      deferredWrites = false;
      if (partitionConsumptionState.isBatchOnly() &&
          this.readOnlyForBatchOnlyStoreEnabled) {
        readOnly = true;
      }
    } else {
      // Prior to the EOP, we can optimize the storage if the data is sorted.
      deferredWrites = sorted;
    }
    storagePartitionConfig.setDeferredWrite(deferredWrites);
    storagePartitionConfig.setReadOnly(readOnly);
    return storagePartitionConfig;
  }

  /**
   * This function checks various conditions to verify if a store is ready to serve.
   *
   * Lag = (Source Max Offset - SOBR Source Offset) - (Current Offset - SOBR Destination Offset)
   *
   * @param partitionConsumptionState
   *
   * @return true if EOP was received and (for hybrid stores) if lag <= threshold
   */
  private boolean isReadyToServe(PartitionConsumptionState partitionConsumptionState) {
    // Check various short-circuit conditions first.
    if (!partitionConsumptionState.isEndOfPushReceived()) {
      // If the EOP has not been received yet, then for sure we aren't ready
      return false;
    }

    if (partitionConsumptionState.isComplete()) {
      //Since we know the EOP has been received, regular batch store is read to go!
      return true;
    }

    if (!partitionConsumptionState.isWaitingForReplicationLag()) {
      // If we have already crossed the acceptable lag threshold in the past, then we will stick to that,
      // rather than possibly flip flopping
      return true;
    }

    boolean lagIsAcceptable;

    if (!hybridStoreConfig.isPresent()) {
    /**
     * In theory, it will be 1 offset ahead of the current offset since getOffset returns the next available offset.
     * Currently, we make it a sloppy in case Kafka topic have duplicate messages.
     * TODO: find a better solution
     */
      lagIsAcceptable = cachedLatestOffsetGetter.getOffset(topic, partitionConsumptionState.getPartition()) <=
        partitionConsumptionState.getOffsetRecord().getOffset() + SLOPPY_OFFSET_CATCHUP_THRESHOLD;
    } else {
      Optional<StoreVersionState> storeVersionStateOptional = storageMetadataService.getStoreVersionState(topic);
      Optional<Long> sobrDestinationOffsetOptional = partitionConsumptionState.getOffsetRecord().getStartOfBufferReplayDestinationOffset();
      if (!(storeVersionStateOptional.isPresent() && sobrDestinationOffsetOptional.isPresent())) {
        // In this case, we have a hybrid store which has received its EOP, but has not yet received its SOBR.
        // Therefore, we cannot precisely measure its offset, but we know for sure that it is lagging since
        // the RT buffer replay has not even started yet.
        logger.warn(consumerTaskId + " Cannot measure replication lag because the SOBR info is not present"
            + ", storeVersionStateOptional: " + storeVersionStateOptional + ", sobrDestinationOffsetOptional: " + sobrDestinationOffsetOptional);
        return false;
      }
      /**
       * startOfBufferReplay could be null if the storeVersionState was deleted without deleting the corresponding
       * partitionConsumptionState. This will cause storeVersionState to be recreated without startOfBufferReplay.
       */
      if (storeVersionStateOptional.get().startOfBufferReplay == null) {
        throw new VeniceInconsistentStoreMetadataException("Inconsistent store metadata detected for topic " + topic
            + ", partition " + partitionConsumptionState.getPartition()
            +". Will clear the metadata and restart ingestion.");
      }

      // Looks like none of the short-circuitry fired, so we need to measure lag!
      long threshold = hybridStoreConfig.get().getOffsetLagThresholdToGoOnline();
      boolean shouldLogLag = partitionConsumptionState.getOffsetRecord().getOffset() % threshold == 0; //Log lag for every <threshold> records.
      long lag = measureHybridOffsetLag(storeVersionStateOptional.get().startOfBufferReplay, partitionConsumptionState, shouldLogLag);
      boolean lagging = lag > threshold;

      if (shouldLogLag) {
      logger.info(String.format("%s partition %d is %slagging. Lag: [%d] %s Threshold [%d]", consumerTaskId,
          partitionConsumptionState.getPartition(), (lagging ? "" : "not "), lag, (lagging ? ">" : "<"), threshold));
      }

      lagIsAcceptable = !lagging;
    }

    if (lagIsAcceptable) {
      partitionConsumptionState.lagHasCaughtUp();
    }

    return lagIsAcceptable;
  }

  /**
   * A private method that has the formula to calculate real-time buffer lag. This method assumes every factor is
   * not null or presented in Optional. Pre-check should be done if necessary
   */
  private long measureHybridOffsetLag(StartOfBufferReplay sobr, PartitionConsumptionState pcs, boolean shouldLog) {
    int partition = pcs.getPartition();
    long currentOffset = pcs.getOffsetRecord().getOffset();
    /**
     * We still allow the upstream to check whether it could become 'ONLINE' for every message since it is possible
     * that there is no messages after rewinding, which causes partition won't be 'ONLINE' in the future.
     *
     * With this strategy, it is possible that partition could become 'ONLINE' at most
     * {@link CachedLatestOffsetGetter#ttlMs} earlier.
     */
    if (bufferReplayEnabledForHybrid) {
      long sourceTopicMaxOffset = cachedLatestOffsetGetter.getOffset(sobr.sourceTopicName.toString(), partition);

      long sobrSourceOffset = sobr.sourceOffsets.get(partition);

      if (!pcs.getOffsetRecord().getStartOfBufferReplayDestinationOffset().isPresent()) {
        throw new IllegalArgumentException("SOBR DestinationOffset is not presented.");
      }

      long sobrDestinationOffset = pcs.getOffsetRecord().getStartOfBufferReplayDestinationOffset().get();

      long lag = (sourceTopicMaxOffset - sobrSourceOffset) - (currentOffset - sobrDestinationOffset);

      if (shouldLog) {
        logger.info(String.format("%s partition %d real-time buffer lag offset is: " + "(Source Max [%d] - SOBR Source [%d]) - (Dest Current [%d] - SOBR Dest [%d]) = Lag [%d]",
            consumerTaskId, partition, sourceTopicMaxOffset, sobrSourceOffset, currentOffset, sobrDestinationOffset, lag));
      }

      return lag;
    } else {
      long storeVersionTopicLatestOffset = cachedLatestOffsetGetter.getOffset(topic, partition);
      long lag = storeVersionTopicLatestOffset - currentOffset;
      if (shouldLog) {
        logger.info(String.format("Store buffer replay was disabled, and %s partition %d lag offset is: (Dest Latest [%d] - Dest Current [%d]) = Lag [%d]",
            consumerTaskId, partition, storeVersionTopicLatestOffset, currentOffset, lag));
      }
      return lag;
    }
  }

  private void processMessages() throws InterruptedException {
    if (null != lastDrainerException) {
      // Interrupt the whole ingestion task
      throw new VeniceException("Exception thrown by store buffer drainer", lastDrainerException);
    }
    /**
     * Check whether current consumer has any subscription or not since 'poll' function will throw
     * {@link IllegalStateException} with empty subscription.
     */
    if (consumer.hasSubscription()) {
      idleCounter = 0;
      long beforePollingTimestamp = System.currentTimeMillis();
      ConsumerRecords<KafkaKey, KafkaMessageEnvelope> records = consumer.poll(readCycleDelayMs);
      long afterPollingTimestamp = System.currentTimeMillis();

      int pollResultNum = 0;
      if (null != records) {
        pollResultNum = records.count();
      }
      if (emitMetrics.get()) {
        storeIngestionStats.recordPollRequestLatency(storeNameWithoutVersionInfo, afterPollingTimestamp - beforePollingTimestamp);
        storeIngestionStats.recordPollResultNum(storeNameWithoutVersionInfo, pollResultNum);
      }

      if (pollResultNum == 0) {
        if (recordsPolled) {     //This is the first time we polled and got an empty response set
          versionedDIVStats.resetCurrentIdleTime(storeNameWithoutVersionInfo, storeVersion);
        } else { //On subsequent empty polls, sleep to reduce thread contention
          Thread.sleep(emptyPollSleepMs);
        }
        recordsPolled = false;
        versionedDIVStats.recordCurrentIdleTime(storeNameWithoutVersionInfo, storeVersion);
        versionedDIVStats.recordOverallIdleTime(storeNameWithoutVersionInfo, storeVersion);
        return;
      } else {
        recordsPolled = true;
      }
      long totalRecordSize = 0;
      for (ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record : records) {
        // blocking call
        storeBufferService.putConsumerRecord(record, this);
        totalRecordSize += record.serializedKeySize() + record.serializedValueSize();
      }
      if (recordsPolled) {
        /**
         * We would like to throttle the ingestion by batch ({@link ConsumerRecords} return by each poll.
         * The batch shouldn't be too big, otherwise, the {@link StoreBufferService#putConsumerRecord(ConsumerRecord, StoreIngestionTask)}
         * could be blocked when the buffer is full, and the throttling could be inaccurate.
         * So every record returned from {@link KafkaConsumerWrapper#poll(long)} should be processed
         * as fast as possible to avoid long-lasting objects in JVM to minimize the 'object copy' time
         * during GC.
         *
         * Here are more details:
         * 1. Previously, the throttling was happening in StoreIngestionTask#processConsumerRecord,
         *    which would be invoked by StoreBufferService;
         * 2. When the ingestion got throttled, the database operations would halt, but the deserialized
         *    records would be kept pushing to the intermediate buffer pool until the pool is full;
         * 3. When Young GC happens, all the objects in the buffer pool could be potentially copied to Survivor
         *    space since they are being actively referenced;
         * 4. The object copy time is the slowest phase in Young GC;
         *
         * By moving the throttling logic after putting deserialized records to buffer, the records in the buffer
         * will be processed as long as there is enough capacity.
         * With this way, the actively referenced object will be reduced greatly during throttling and Young GC
         * won't need to copy many objects from Young regions to Survivor regions, which reduces the overall
         * GC pause time.
         */
        bandwidthThrottler.maybeThrottle(totalRecordSize);
        recordsThrottler.maybeThrottle(records.count());
      }
      long afterPutTimestamp = System.currentTimeMillis();
      if (emitMetrics.get()) {
        storeIngestionStats.recordConsumerRecordsQueuePutLatency(storeNameWithoutVersionInfo, afterPutTimestamp - afterPollingTimestamp);
      }
    } else {
      idleCounter ++;
      if(idleCounter > MAX_IDLE_COUNTER) {
        logger.warn(consumerTaskId + " No Partitions are subscribed to for store attempts expired after " + idleCounter);
        complete();
      } else {
        logger.warn(consumerTaskId + " No Partitions are subscribed to for store attempt " + idleCounter);
        Utils.sleep(readCycleDelayMs);
      }
    }
  }

  /**
   * Polls the producer for new messages in an infinite loop by a dedicated consumer thread
   * and processes the new messages by current thread.
   */
  @Override
  public void run() {
    // Update thread name to include topic to make it easy debugging
    Thread.currentThread().setName("venice-consumer-" + topic);

    logger.info("Running " + consumerTaskId);
    try {
      this.consumer = factory.getConsumer(kafkaProps);
      /**
       * Here we could not use isRunning() since it is a synchronized function, whose lock could be
       * acquired by some other synchronized function, such as {@link #kill()}.
        */
      while (isRunning.get()) {
        processConsumerActions();
        processMessages();
      }

      // If the ingestion task is stopped gracefully (server stops or kill push job), persist processed offset to disk
      for (PartitionConsumptionState partitionConsumptionState : partitionConsumptionStateMap.values()) {
        syncOffset(topic, partitionConsumptionState);
      }

    } catch (VeniceIngestionTaskKilledException ke){
      logger.info(consumerTaskId+"is killed, start to report to notifier.", ke);
      notificationDispatcher.reportKilled(partitionConsumptionStateMap.values(), ke);
    } catch (Exception e) {
      // After reporting error to controller, controller will ignore the message from this replica if job is aborted.
      // So even this storage node recover eventually, controller will not confused.
      // If job is not aborted, controller is open to get the subsequent message from this replica(if storage node was
      // recovered, it will send STARTED message to controller again)
      logger.error(consumerTaskId + " failed with Exception.", e);
      notificationDispatcher.reportError(partitionConsumptionStateMap.values(), "Exception caught during poll.", e);
    } catch (Throwable t) {
      logger.error(consumerTaskId + " failed with Throwable!!!", t);
      notificationDispatcher.reportError(partitionConsumptionStateMap.values(), "Non-exception Throwable caught in " + getClass().getSimpleName() +
          "'s run() function.", new VeniceException(t));
    } finally {
      internalClose();
    }
  }

  private void internalClose() {
    // Only reset Offset Messages are important, subscribe/unSubscribe will be handled
    // on the restart by Helix Controller notifications on the new StoreIngestionTask.
    for(ConsumerAction message : consumerActionsQueue) {
      ConsumerActionType opType = message.getType();
      if(opType == ConsumerActionType.RESET_OFFSET) {
        String topic = message.getTopic();
        int partition = message.getPartition();
        logger.info(consumerTaskId + " Cleanup Reset OffSet : Topic " + topic + " Partition Id " + partition );
        storageMetadataService.clearOffset(topic, partition);
      } else {
        logger.info(consumerTaskId + " Cleanup ignoring the Message " + message);
      }
    }

    if(consumer == null) {
      // Consumer constructor error-ed out, nothing can be cleaned up.
      logger.info("Error in consumer creation, skipping close for topic " + topic);
      return;
    }
    consumer.close();
    isRunning.set(false);
    logger.info("Store ingestion task for store: " + topic + " is closed");
  }

  /**
   * Consumes the kafka actions messages in the queue.
   */
  private void processConsumerActions() {
    Iterator<ConsumerAction> iter = consumerActionsQueue.iterator();
    while (iter.hasNext()) {
      // Do not want to remove a message from the queue unless it has been processed.
      ConsumerAction message = iter.next();
      try {
        message.incrementAttempt();
        processConsumerAction(message);
      } catch (InterruptedException e){
        // task is killed
        throw new VeniceIngestionTaskKilledException(topic, e);
      } catch (Exception ex) {
        if (message.getAttemptsCount() < MAX_CONTROL_MESSAGE_RETRIES) {
          logger.info("Error Processing message will retry later" + message , ex);
          return;
        } else {
          logger.info("Ignoring message:  " + message + " after retries " + message.getAttemptsCount(), ex);
        }
      }
      iter.remove();
    }
  }

  private void processConsumerAction(ConsumerAction message)
      throws InterruptedException {
    ConsumerActionType operation = message.getType();
    String topic = message.getTopic();
    int partition = message.getPartition();
    switch (operation) {
      case SUBSCRIBE:
        // Drain the buffered message by last subscription.
        storeBufferService.drainBufferedRecordsFromTopicPartition(topic, partition);

        OffsetRecord record = storageMetadataService.getLastOffset(topic, partition);

        // First let's try to restore the state retrieved from the OffsetManager
        PartitionConsumptionState newPartitionConsumptionState = new PartitionConsumptionState(partition, record, hybridStoreConfig.isPresent(), isIncrementalPushEnabled);
        partitionConsumptionStateMap.put(partition,  newPartitionConsumptionState);
        record.getProducerPartitionStateMap().entrySet().stream().forEach(entry -> {
              GUID producerGuid = GuidUtils.getGuidFromCharSequence(entry.getKey());
              ProducerTracker producerTracker = producerTrackerMap.computeIfAbsent(producerGuid, producerTrackerCreator);
              producerTracker.setPartitionState(partition, entry.getValue());
              producerTrackerMap.put(producerGuid, producerTracker);
            }
        );

        // Once storage node restart, send the "START" status to controller to rebuild the task status.
        // If this storage node has ever consumed data from this topic, instead of sending "START" here, we send it
        // once START_OF_PUSH message has been read.
        if (record.getOffset() > 0) {
          Optional<StoreVersionState> storeVersionState = storageMetadataService.getStoreVersionState(topic);
          boolean sorted;
          if (storeVersionState.isPresent()) {
            /**
             * This is where the field should be for any push that begun after the introduction of
             * {@link StoreVersionState} v1.
             */
            sorted = storeVersionState.get().sorted;
          } else {
            /**
             * This is the fall-back, if we don't find the field in its intended place.
             *
             * This could happen the first time a Storage Node bounces after being upgraded to the the
             * current code, for any store-version which was pushed prior to the introduction of
             * {@link StoreVersionState} v1. After this first Storage node bounce, this code
             * should never run again and could be removed...
             *
             * In that case, we will run {@link #retrieveSortedFieldFromStartOfPush(int)} in order to
             * attempt to populate the {@link StoreVersionState}.
             */
            sorted = retrieveSortedFieldFromStartOfPush(partition);

            StoreVersionState newStoreVersionState = new StoreVersionState();
            newStoreVersionState.sorted = sorted;
            storageMetadataService.put(topic, newStoreVersionState);
            logger.info(consumerTaskId + " We did not find a StoreVersionState for partition " + partition +
                " at consumer subscription time. We will generate a new StoreVersionState with sorted=" + sorted +
                " and store it persistently.");
          }
          /**
           * Notify the underlying store engine about starting batch push.
           */
          beginBatchWrite(partition, sorted, newPartitionConsumptionState);

          notificationDispatcher.reportRestarted(newPartitionConsumptionState);
        }
        /**
         * TODO: The behavior for completed partition is not consistent here.
         *
         * When processing subscription action for restart scenario, {@link #consumer} won't subscribe the topic
         * partition if it is already completed.
         * In normal case (not completed right away), {@link #consumer} will continue subscribing the topic partition
         * even after receiving the 'EOP' control message (no auto-unsubscription happens).
         *
         * From my understanding, at least we should keep them consistent to avoid confusion.
         *
         * Possible proposals:
         * 1. (Preferred) Auto-unsubscription when receiving EOP for batch store. With this way,
         * the unused consumer thread (not processing any kafka message) will be collected.
         * 2. Always keep subscription no matter what happens.
         */

        // Second, take care of informing the controller about our status, and starting consumption
          /**
           * There could be two cases in this scenario:
           * 1. The job is completed, so Controller will ignore any status message related to the completed/archived job.
           * 2. The job is still running: some partitions are in 'ONLINE' state, but other partitions are still in
           * 'BOOTSTRAP' state.
           * In either case, StoreIngestionTask should report 'started' => ['progress' => ] 'completed' to accomplish
           * task state transition in Controller.
           */
        try {
          if (isReadyToServe(newPartitionConsumptionState)) {
            notificationDispatcher.reportCompleted(newPartitionConsumptionState);
            logger.info(consumerTaskId + " Partition " + partition + " is already ready to serve");
          }
        } catch (VeniceInconsistentStoreMetadataException e) {
          storeIngestionStats.recordInconsistentStoreMetadata(storeNameWithoutVersionInfo, 1);
          // clear the local store metadata and the replica will be rebuilt from scratch upon retry as part of
          // processConsumerActions.
          storageMetadataService.clearOffset(topic, partition);
          storageMetadataService.clearStoreVersionState(topic);
          producerTrackerMap.values().forEach(
              producerTracker -> producerTracker.clearPartition(partition)
          );
          throw e;
        }
        consumer.subscribe(topic, partition, record);
        logger.info(consumerTaskId + " subscribed to: Topic " + topic + " Partition Id " + partition + " Offset "
            + record.getOffset());
        break;
      case UNSUBSCRIBE:
        logger.info(consumerTaskId + " UnSubscribed to: Topic " + topic + " Partition Id " + partition);
        consumer.unSubscribe(topic, partition);
        // Drain the buffered message by last subscription.
        storeBufferService.drainBufferedRecordsFromTopicPartition(topic, partition);
        /**
         * Since the processing of the buffered messages are using {@link #partitionConsumptionStateMap} and
         * {@link #producerTrackerMap}, we would like to drain all the buffered messages before cleaning up those
         * two variables to avoid the race condition.
          */
        partitionConsumptionStateMap.remove(partition);
        producerTrackerMap.values().stream().forEach(
            producerTracker -> producerTracker.clearPartition(partition)
        );
        break;
      case RESET_OFFSET:
        try {
          consumer.resetOffset(topic, partition);
          logger.info(consumerTaskId + " Reset OffSet : Topic " + topic + " Partition Id " + partition);
        } catch (UnsubscribedTopicPartitionException e) {
          logger.info(consumerTaskId + " No need to reset offset by Kafka consumer, since the consumer is not " +
              "subscribing Topic: " + topic + " Partition Id: " + partition);
        }
        partitionConsumptionStateMap.put(
            partition,
            new PartitionConsumptionState(partition, new OffsetRecord(), hybridStoreConfig.isPresent(), isIncrementalPushEnabled));
        producerTrackerMap.values().stream().forEach(
            producerTracker -> producerTracker.clearPartition(partition)
        );
        storageMetadataService.clearOffset(topic, partition);
        break;
      case KILL:
        logger.info("Kill this consumer task for Topic:" + topic);
        // Throw the exception here to break the consumption loop, and then this task is marked as error status.
        throw new InterruptedException("Received the signal to kill this consumer. Topic " + topic);
      default:
        throw new UnsupportedOperationException(operation.name() + "not implemented.");
    }
  }

  /**
   * This function is for data migration purposes. In order to populate missing StoreVersionState
   * records for already-existing store-versions pushed prior to the upgrade which include the
   * schema for StoreVersionState.
   *
   * TODO: Remove this function when the data migration is completed...
   *
   * @param partitionId for which to retrieve the SOP's sorted field.
   * @return the value of the sorted field in the SOP for that partition.
   */
  @Deprecated
  private boolean retrieveSortedFieldFromStartOfPush(int partitionId) {
    int consumptionAttempt = 0;
    int recordsConsumed = 0;
    final int MAX_ATTEMPTS = 10;
    final int MAX_RECORDS = 1000;
    try (KafkaConsumerWrapper tempConsumer = factory.getConsumer(kafkaProps)) {
      tempConsumer.subscribe(topic, partitionId, new OffsetRecord());
      ConsumerRecords<KafkaKey, KafkaMessageEnvelope> consumerRecords;
      while (consumptionAttempt < MAX_ATTEMPTS && recordsConsumed < MAX_RECORDS) {
        consumerRecords = tempConsumer.poll(readCycleDelayMs);
        if (consumerRecords.isEmpty()) {
          consumptionAttempt++;
          logger.info(consumerTaskId + " Got nothing out of Kafka while attempting to" +
              " retrieveSortedFieldFromStartOfPush() for partition " + partitionId +
              " on attempt " + consumptionAttempt + "/" + MAX_ATTEMPTS);
        } else {
          Iterator<ConsumerRecord<KafkaKey, KafkaMessageEnvelope>> iterator = consumerRecords.iterator();
          while (iterator.hasNext()) {
            recordsConsumed++;
            ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record = iterator.next();
            if (record.key().isControlMessage()) {
              ControlMessage controlMessage = (ControlMessage) record.value().payloadUnion;
              if (ControlMessageType.valueOf(controlMessage) == ControlMessageType.START_OF_PUSH) {
                StartOfPush startOfPush = (StartOfPush) controlMessage.controlMessageUnion;
                logger.info(consumerTaskId + " Successfully retrieved the START_OF_PUSH message from partition " +
                    partitionId + " at offset " + record.offset() + ". Returning sorted=" + startOfPush.sorted);
                return startOfPush.sorted;
              }
            }
          }
          logger.info(consumerTaskId + " Consumed " + recordsConsumed + " messages from partition " + partitionId +
              " without finding any START_OF_PUSH control messages... Will keep trying until consuming" +
              MAX_RECORDS + " at most.");
        }
      }
    }
    // TODO: Evaluate if this behavior is too stringent, and relax it if it is...
    throw new VeniceException(consumerTaskId + " Could not find a StartOfPush message in partition" + partitionId +
        ". Consumer poll attempts that yielded zero records: " + consumptionAttempt + "/" + MAX_ATTEMPTS +
        ". Number of real records consumed: " + recordsConsumed + "/" + MAX_RECORDS);
  }

  private boolean shouldProcessRecord(ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record) {
    String recordTopic = record.topic();
    if(!topic.equals(recordTopic)) {
      throw new VeniceMessageException(consumerTaskId + "Message retrieved from different topic. Expected " + this.topic + " Actual " + recordTopic);
    }

    int partitionId = record.partition();
    PartitionConsumptionState partitionConsumptionState = partitionConsumptionStateMap.get(partitionId);
    if(null == partitionConsumptionState) {
      logger.info("Skipping message as partition is no longer actively subscribed. Topic: " + topic + " Partition Id: " + partitionId);
      return false;
    }
    long lastOffset = partitionConsumptionState.getOffsetRecord()
        .getOffset();
    if(lastOffset >= record.offset()) {
      logger.info(consumerTaskId + "The record was already processed Partition" + partitionId + " LastKnown " + lastOffset + " Current " + record.offset());
      return false;
    }

    if (partitionConsumptionState.isEndOfPushReceived() &&
        partitionConsumptionState.isBatchOnly()) {

      KafkaKey key = record.key();
      KafkaMessageEnvelope value = record.value();
      if (key.isControlMessage() &&
          ControlMessageType.valueOf((ControlMessage)value.payloadUnion) == ControlMessageType.END_OF_SEGMENT) {
        // Still allow END_OF_SEGMENT control message
        return true;
      }
      // emit metric for unexpected messages
      if (emitMetrics.get()) {
        storeIngestionStats.recordUnexpectedMessage(storeNameWithoutVersionInfo, 1);
      }

      if (record.offset() % 100 == 0) {
          // Report such kind of message once per 100 messages to reduce logging volume
          /**
           * TODO: right now, if we update a store to enable hybrid, {@link StoreIngestionTask} for the existing versions
           * won't know it since {@link #hybridStoreConfig} parameter is passed during construction.
           *
           * Same thing for {@link #isIncrementalPushEnabled}.
           *
           * So far, to make hybrid store/incremental store work, customer needs to do a new push after enabling hybrid/
           * incremental push feature of the store.
           */
          logger.warn(
              "The record was received after 'EOP', but the store: " + topic +
                  " is neither hybrid nor incremental push enabled, so will skip it.");
      }
      return false;
    }

    return true;
  }

  /**
   * This function will be invoked in {@link StoreBufferService} to process buffered {@link ConsumerRecord}.
   * @param record
   */
  public void processConsumerRecord(ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record) {
    int partition = record.partition();
    //The partitionConsumptionStateMap can be modified by other threads during consumption (for example when unsubscribing)
    //in order to maintain thread safety, we hold onto the reference to the partitionConsumptionState and pass that
    //reference to all downstream methods so that all offset persistence operations use the same partitionConsumptionState
    PartitionConsumptionState partitionConsumptionState = partitionConsumptionStateMap.get(partition);
    if (null == partitionConsumptionState) {
      logger.info("Topic " + topic + " Partition " + partition + " has been unsubscribed, skip this record that has offset " + record.offset());
      return;
    }
    if (partitionConsumptionState.isErrorReported()) {
      logger.info("Topic " + topic + " Partition " + partition + " is already errored, skip this record that has offset " + record.offset());
      return;
    }
    if(!shouldProcessRecord(record)) {
      return;
    }
    int recordSize = 0;
    try {
      recordSize = internalProcessConsumerRecord(record, partitionConsumptionState);
    } catch (FatalDataValidationException e) {
      int faultyPartition = record.partition();
      String errorMessage = "Fatal data validation problem with partition " + faultyPartition;
      boolean needToUnsub = !(isCurrentVersion.getAsBoolean() || partitionConsumptionState.isEndOfPushReceived());
      if (needToUnsub) {
        errorMessage += ". Consumption will be halted.";
      } else {
        errorMessage += ". Because " + topic + " is the current version, consumption will continue.";
      }
      notificationDispatcher.reportError(Arrays.asList(partitionConsumptionState), errorMessage, e);
      if (needToUnsub) {
        unSubscribePartition(topic, faultyPartition);
      }
    } catch (VeniceMessageException | UnsupportedOperationException ex) {
      throw new VeniceException(consumerTaskId + " : Received an exception for message at partition: "
          + record.partition() + ", offset: " + record.offset() + ". Bubbling up.", ex);
    }

    partitionConsumptionState.incrementProcessedRecordNum();
    partitionConsumptionState.incrementProcessedRecordSize(recordSize);

    if (diskUsage.isDiskFull(recordSize)){
      throw new VeniceException("Disk is full: throwing exception to error push: "
          + storeNameWithoutVersionInfo + " version " + storeVersion + ". "
          + diskUsage.getDiskStatus());
    }

    int processedRecordNum = partitionConsumptionState.getProcessedRecordNum();
    if (processedRecordNum >= OFFSET_REPORTING_INTERVAL ||
        partitionConsumptionState.isEndOfPushReceived()) {
      int processedRecordSize = partitionConsumptionState.getProcessedRecordSize();
      // Report metrics
      if (emitMetrics.get()) {
        storeIngestionStats.recordBytesConsumed(storeNameWithoutVersionInfo, processedRecordSize);
        storeIngestionStats.recordRecordsConsumed(storeNameWithoutVersionInfo, processedRecordNum);
      }

      partitionConsumptionState.resetProcessedRecordNum();
      partitionConsumptionState.resetProcessedRecordSize();
    }

    partitionConsumptionState.incrementProcessedRecordSizeSinceLastSync(recordSize);
    long syncBytesInterval = partitionConsumptionState.isDeferredWrite() ? databaseSyncBytesIntervalForDeferredWriteMode
        : databaseSyncBytesIntervalForTransactionalMode;

    // If the following condition is true, then we want to sync to disk.
    boolean recordsProcessedAboveSyncIntervalThreshold = false;
    if (syncBytesInterval > 0 && (partitionConsumptionState.getProcessedRecordSizeSinceLastSync() >= syncBytesInterval)) {
      recordsProcessedAboveSyncIntervalThreshold = true;
      syncOffset(topic, partitionConsumptionState);
    }

    boolean endOfPushReceived = partitionConsumptionState.isEndOfPushReceived();
    boolean isCompletionReported = partitionConsumptionState.isCompletionReported();
    if (recordsProcessedAboveSyncIntervalThreshold || (endOfPushReceived && !isCompletionReported)) {
      if (isReadyToServe(partitionConsumptionState)) {
        notificationDispatcher.reportCompleted(partitionConsumptionState);
      } else {
        notificationDispatcher.reportProgress(partitionConsumptionState);
      }
    }
  }

  private void syncOffset(String topic, PartitionConsumptionState ps) {
    int partition = ps.getPartition();
    AbstractStorageEngine storageEngine = storeRepository.getLocalStorageEngine(topic);
    Map<String, String> dbCheckpointingInfo = storageEngine.sync(partition);
    OffsetRecord offsetRecord = ps.getOffsetRecord();
    /**
     * The reason to transform the internal state only during checkpointing is that
     * the intermediate checksum generation is an expensive operation.
     * See {@link ProducerTracker#addMessage(int, KafkaKey, KafkaMessageEnvelope, boolean, Optional)}
     * to find more details.
     */
    offsetRecord.transform();
    // Checkpointing info required by the underlying storage engine
    offsetRecord.setDatabaseInfo(dbCheckpointingInfo);
    storageMetadataService.put(this.topic, partition, offsetRecord);
    ps.resetProcessedRecordSizeSinceLastSync();
  }

  public void setLastDrainerException(Exception e) {
    lastDrainerException = e;
  }

  /**
   * @return the total lag for all subscribed partitions between the real-time buffer topic and this consumption task.
   */
  public long getRealTimeBufferOffsetLag() {
    if (!hybridStoreConfig.isPresent()) {
      return METRIC_ONLY_AVAILABLE_FOR_HYBRID_STORES.code;
    }

    Optional<StoreVersionState> svs = storageMetadataService.getStoreVersionState(topic);
    if (!svs.isPresent()) {
      return STORE_VERSION_STATE_UNAVAILABLE.code;
    }

    if (partitionConsumptionStateMap.isEmpty()) {
      return NO_SUBSCRIBED_PARTITION.code;
    }

    long offsetLag = partitionConsumptionStateMap.values().parallelStream()
        .filter(pcs -> pcs.isEndOfPushReceived() &&
            pcs.getOffsetRecord().getStartOfBufferReplayDestinationOffset().isPresent())
        .mapToLong(pcs -> measureHybridOffsetLag(svs.get().startOfBufferReplay, pcs, false))
        .sum();

    return minZeroLag(offsetLag);
  }

  /**
   * Because of timing considerations, it is possible that some lag metrics could compute negative
   * values. Negative lag does not make sense so the intent is to ease interpretation by applying a
   * lower bound of zero on these metrics...
   */
  private long minZeroLag(long value) {
    if (value < 0) {
      logger.debug("Got a negative value for a lag metric. Will report zero.");
      return 0;
    } else {
      return value;
    }
  }

  /**
   * Indicate the number of partitions that haven't received SOBR yet. This method is for Hybrid store
   */
  public long getNumOfPartitionsNotReceiveSOBR() {
    return partitionConsumptionStateMap.values().parallelStream()
        .filter(pcs -> !pcs.getOffsetRecord().getStartOfBufferReplayDestinationOffset().isPresent()).count();
  }

  public boolean isHybridMode() {
    if (hybridStoreConfig == null) {
      logger.error("hybrid config shouldn't be null. Something bad happened. Topic: " + getTopic());
    }
    return hybridStoreConfig != null && hybridStoreConfig.isPresent();
  }

  /**
   * In this method, we pass both offset and partitionConsumptionState(ps). The reason behind it is that ps's
   * offset is stale and is not updated until the very end
   */
  private ControlMessageType processControlMessage(ControlMessage controlMessage, int partition, long offset, PartitionConsumptionState partitionConsumptionState) {
    Optional<StoreVersionState> storeVersionState;
    ControlMessageType type = ControlMessageType.valueOf(controlMessage);
    logger.info(consumerTaskId + " : Received " + type.name() + " control message. Partition: " + partition + ", Offset: " + offset);
    AbstractStorageEngine storageEngine = storeRepository.getLocalStorageEngine(topic);
    switch(type) {
      case START_OF_PUSH:
        StartOfPush startOfPush = (StartOfPush) controlMessage.controlMessageUnion;
        /**
         * Notify the underlying store engine about starting batch push.
         */
        beginBatchWrite(partition, startOfPush.sorted, partitionConsumptionState);

        notificationDispatcher.reportStarted(partitionConsumptionState);
        storeVersionState = storageMetadataService.getStoreVersionState(topic);
        if (!storeVersionState.isPresent()) {
          // No other partition of the same topic has started yet, let's initialize the StoreVersionState
          StoreVersionState newStoreVersionState = new StoreVersionState();
          newStoreVersionState.sorted = startOfPush.sorted;
          newStoreVersionState.chunked = startOfPush.chunked;
          newStoreVersionState.compressionStrategy = startOfPush.compressionStrategy;
          storageMetadataService.put(topic, newStoreVersionState);
        } else if (storeVersionState.get().sorted != startOfPush.sorted) {
          // Something very wrong is going on ): ...
          throw new VeniceException("Unexpected: received multiple " + type.name() +
              " control messages with inconsistent 'sorted' fields within the same topic!");
        } else if (storeVersionState.get().chunked != startOfPush.chunked) {
          // Something very wrong is going on ): ...
          throw new VeniceException("Unexpected: received multiple " + type.name() +
              " control messages with inconsistent 'chunked' fields within the same topic!");
        } // else, no need to persist it once more.
        break;
      case END_OF_PUSH:
        // We need to keep track of when the EOP happened, as that is used within Hybrid Stores' lag measurement
        partitionConsumptionState.getOffsetRecord().endOfPushReceived(offset);

        /**
         * Right now, we assume there are no sorted message after EndOfPush control message.
         * TODO: if this behavior changes in the future, the logic needs to be adjusted as well.
         */
        StoragePartitionConfig storagePartitionConfig = getStoragePartitionConfig(partition, false, partitionConsumptionState);

        /**
         * Update the transactional/deferred mode of the partition.
         */
        partitionConsumptionState.setDeferredWrite(storagePartitionConfig.isDeferredWrite());

        /**
         * Indicate the batch push is done, and the internal storage engine needs to do some cleanup.
         */
        storageEngine.endBatchWrite(storagePartitionConfig);

        /**
         * It's a bit of tricky here. Since the offset is not updated yet, it's actually previous offset reported
         * here.
         * TODO: sync up offset before invoking dispatcher
         */
        notificationDispatcher.reportEndOfPushReceived(partitionConsumptionState);
        break;
      case START_OF_SEGMENT:
      case END_OF_SEGMENT:
        /**
         * No-op for {@link ControlMessageType#START_OF_SEGMENT} and {@link ControlMessageType#END_OF_SEGMENT}.
         * These are handled in the {@link ProducerTracker}.
         */
        break;
      case START_OF_BUFFER_REPLAY:
        storeVersionState = storageMetadataService.getStoreVersionState(topic);
        if (storeVersionState.isPresent()) {
          // Update StoreVersionState, if necessary
          StartOfBufferReplay startOfBufferReplay = (StartOfBufferReplay) controlMessage.controlMessageUnion;
          if (null == storeVersionState.get().startOfBufferReplay) {
            // First time we receive a SOBR
            storeVersionState.get().startOfBufferReplay = startOfBufferReplay;
            storageMetadataService.put(topic, storeVersionState.get());
          } else if (!Utils.listEquals(storeVersionState.get().startOfBufferReplay.sourceOffsets, startOfBufferReplay.sourceOffsets)) {
            // Something very wrong is going on ): ...
            throw new VeniceException("Unexpected: received multiple " + type.name() +
                " control messages with inconsistent 'startOfBufferReplay.offsets' fields within the same topic!" +
                "\nPrevious SOBR: " + storeVersionState.get().startOfBufferReplay.sourceOffsets +
                "\nIncoming SOBR: " + startOfBufferReplay.sourceOffsets);
          }

          partitionConsumptionState.getOffsetRecord().setStartOfBufferReplayDestinationOffset(offset);
          notificationDispatcher.reportStartOfBufferReplayReceived(partitionConsumptionState);
        } else {
          // TODO: If there are cases where this can legitimately happen, then we need a less stringent reaction here...
          throw new VeniceException("Unexpected: received some " + type.name() +
              " control message in a topic where we have not yet received a " +
              ControlMessageType.START_OF_PUSH.name() + " control message.");
        }
       break;
      case START_OF_INCREMENTAL_PUSH:
        CharSequence startVersion = ((StartOfIncrementalPush) controlMessage.controlMessageUnion).version;
        IncrementalPush newIncrementalPush = new IncrementalPush();
        newIncrementalPush.version = startVersion;

        partitionConsumptionState.setIncrementalPush(newIncrementalPush);
        notificationDispatcher.reportStartOfIncrementalPushReceived(partitionConsumptionState, startVersion.toString());
        break;
      case END_OF_INCREMENTAL_PUSH:
        // TODO: it is possible that we could turn incremental store to be read-only when incremental push is done
        CharSequence endVersion = ((EndOfIncrementalPush) controlMessage.controlMessageUnion).version;
        //reset incremental push version
        partitionConsumptionState.setIncrementalPush(null);
        notificationDispatcher.reportEndOfIncrementalPushRecived(partitionConsumptionState, endVersion.toString());
        break;
      default:
        throw new UnsupportedMessageTypeException("Unrecognized Control message type " + controlMessage.controlMessageType);
    }

    return type;
  }

  /**
   * Process the message consumed from Kafka by de-serializing it and persisting it with the storage engine.
   *
   * @param consumerRecord {@link ConsumerRecord} consumed from Kafka.
   * @return the size of the data written to persistent storage.
   */
  private int internalProcessConsumerRecord(ConsumerRecord<KafkaKey, KafkaMessageEnvelope> consumerRecord,
                                            PartitionConsumptionState partitionConsumptionState) {
    // De-serialize payload into Venice Message format
    KafkaKey kafkaKey = consumerRecord.key();
    KafkaMessageEnvelope kafkaValue = consumerRecord.value();
    int sizeOfPersistedData = 0;
    boolean syncOffset = false;

    Optional<OffsetRecordTransformer> offsetRecordTransformer = Optional.empty();
    try {
      // Assumes the timestamp on the ConsumerRecord is the broker's timestamp when it received the message.
      recordWriterStats(kafkaValue.producerMetadata.messageTimestamp, consumerRecord.timestamp(),
          System.currentTimeMillis());
      FatalDataValidationException e = null;
      boolean endOfPushReceived = partitionConsumptionState.isEndOfPushReceived();
      try {
        offsetRecordTransformer = Optional.of(validateMessage(consumerRecord.partition(), kafkaKey, kafkaValue, endOfPushReceived));
        versionedDIVStats.recordSuccessMsg(storeNameWithoutVersionInfo, storeVersion);
      } catch (FatalDataValidationException fatalException) {
        divErrorMetricCallback.get().execute(fatalException);
        if (endOfPushReceived) {
          e = fatalException;
        } else {
          throw fatalException;
        }
      }

      if (kafkaKey.isControlMessage()) {
        ControlMessage controlMessage = (ControlMessage) kafkaValue.payloadUnion;
        processControlMessage(controlMessage, consumerRecord.partition(), consumerRecord.offset(), partitionConsumptionState);
        /**
         * Here, we want to sync offset/producer guid info whenever receiving any control message:
         * 1. We want to keep the critical milestones.
         * 2. We want to keep all the historical producer guid info since they could be used after. New producer guid
         * is already coming with Control Message.
         *
         * If we don't sync offset this way, it is very possible to encounter
         * {@link com.linkedin.venice.kafka.validation.ProducerTracker.DataFaultType.UNREGISTERED_PRODUCER} since
         * {@link syncOffset} is not being called for every message, we might miss some historical producer guids.
         */
        syncOffset = true;
      } else if (null == kafkaValue) {
        throw new VeniceMessageException(consumerTaskId + " : Given null Venice Message. Partition " +
              consumerRecord.partition() + " Offset " + consumerRecord.offset());
      } else {
        int keySize = kafkaKey.getKeyLength();
        int valueSize = processVeniceMessage(kafkaKey, kafkaValue, consumerRecord.partition());
        if (emitMetrics.get()) {
          storeIngestionStats.recordKeySize(storeNameWithoutVersionInfo, keySize);
          storeIngestionStats.recordValueSize(storeNameWithoutVersionInfo, valueSize);
        }
        sizeOfPersistedData = keySize + valueSize;
      }

      if (e != null) {
        throw e;
      }
    } catch (DuplicateDataException e) {
      versionedDIVStats.recordDuplicateMsg(storeNameWithoutVersionInfo, storeVersion);
      if (logger.isDebugEnabled()) {
        logger.debug(consumerTaskId + " : Skipping a duplicate record at offset: " + consumerRecord.offset());
      }
    } catch (PersistenceFailureException ex) {
      if (partitionConsumptionStateMap.containsKey(consumerRecord.partition())) {
        // If we actually intend to be consuming this partition, then we need to bubble up the failure to persist.
        logger.error("Met PersistenceFailureException while processing record with offset: " + consumerRecord.offset() +
        ", topic: " + topic + ", meta data of the record: " + consumerRecord.value().producerMetadata);
        throw ex;
      } else {
        /*
         * We can ignore this exception for unsubscribed partitions. The unsubscription of partitions in Kafka Consumer
         * is an asynchronous event. Thus, it is possible that the partition has been dropped from the local storage
         * engine but was not yet unsubscribed by the Kafka Consumer, therefore, leading to consumption of useless messages.
         */
        return 0;
      }

      /** N.B.: In either branches of the if, above, we don't want to update the {@link OffsetRecord} */
    }

    // We want to update offsets in all cases, except when we hit the PersistenceFailureException
    if (null == partitionConsumptionState) {
      logger.info("Topic " + topic + " Partition " + consumerRecord.partition() + " has been unsubscribed, will skip offset update");
    } else {
      OffsetRecord offsetRecord = partitionConsumptionState.getOffsetRecord();
      if (offsetRecordTransformer.isPresent()) {
        /**
         * The reason to transform the internal state only during checkpointing is that
         * the intermediate checksum generation is an expensive operation.
         * See {@link ProducerTracker#addMessage(int, KafkaKey, KafkaMessageEnvelope, boolean, Optional)}
         * to find more details.
         */
        offsetRecord.setOffsetRecordTransformer(offsetRecordTransformer.get());
      } /** else, {@link #validateMessage(int, KafkaKey, KafkaMessageEnvelope)} threw a {@link DuplicateDataException} */

      offsetRecord.setOffset(consumerRecord.offset());
      partitionConsumptionState.setOffsetRecord(offsetRecord);
      if (syncOffset) {
        syncOffset(topic, partitionConsumptionState);
      }
    }
    return sizeOfPersistedData;
  }

  private void recordWriterStats(long producerTimestampMs, long brokerTimestampMs, long consumerTimestampMs) {
    long producerBrokerLatencyMs = Math.max(brokerTimestampMs - producerTimestampMs, 0);
    long brokerConsumerLatencyMs = Math.max(consumerTimestampMs - brokerTimestampMs, 0);
    long producerConsumerLatencyMs = Math.max(consumerTimestampMs - producerTimestampMs, 0);
    versionedDIVStats.recordProducerBrokerLatencyMs(storeNameWithoutVersionInfo, storeVersion, producerBrokerLatencyMs);
    versionedDIVStats.recordBrokerConsumerLatencyMs(storeNameWithoutVersionInfo, storeVersion, brokerConsumerLatencyMs);
    versionedDIVStats.recordProducerConsumerLatencyMs(storeNameWithoutVersionInfo, storeVersion,
        producerConsumerLatencyMs);
  }

  private OffsetRecordTransformer validateMessage(int partition, KafkaKey key, KafkaMessageEnvelope message, boolean endOfPushReceived) {
    final GUID producerGUID = message.producerMetadata.producerGUID;
    ProducerTracker producerTracker = producerTrackerMap.computeIfAbsent(producerGUID, producerTrackerCreator);

    return producerTracker.addMessage(partition, key, message, endOfPushReceived, divErrorMetricCallback);
  }

  /**
   * @return the size of the data which was written to persistent storage.
   */
  private int processVeniceMessage(KafkaKey kafkaKey, KafkaMessageEnvelope kafkaValue, int partition) {
    long startTimeNs = -1;
    AbstractStorageEngine storageEngine = storeRepository.getLocalStorageEngine(topic);

    byte[] keyBytes = kafkaKey.getKey();

    switch (MessageType.valueOf(kafkaValue)) {
      case PUT:
        if (logger.isTraceEnabled()) {
          startTimeNs = System.nanoTime();
        }
        // If single-threaded, we can re-use (and clobber) the same Put instance. // TODO: explore GC tuning later.
        Put put = (Put) kafkaValue.payloadUnion;
        // Validate schema id first
        checkValueSchemaAvail(put.schemaId);
        ByteBuffer putValue = put.putValue;
        int valueLen = putValue.remaining();
        /**
         * Since {@link com.linkedin.venice.serialization.avro.OptimizedKafkaValueSerializer} reuses the original byte
         * array, which is big enough to pre-append schema id, so we just reuse it to avoid unnecessary byte array allocation.
         */
        if (putValue.position() < ByteUtils.SIZE_OF_INT) {
          throw new VeniceException("Start position of 'putValue' ByteBuffer shouldn't be less than " + ByteUtils.SIZE_OF_INT);
        }
        // Back up the original 4 bytes
        putValue.position(putValue.position() - ByteUtils.SIZE_OF_INT);
        int backupBytes = putValue.getInt();
        putValue.position(putValue.position() - ByteUtils.SIZE_OF_INT);
        ByteUtils.writeInt(putValue.array(), put.schemaId, putValue.position());
        storageEngine.put(partition, keyBytes, putValue);
        /**
         * We still want to recover the original position to make this function idempotent.
         */
        putValue.putInt(backupBytes);

        if (logger.isTraceEnabled()) {
          logger.trace(consumerTaskId + " : Completed PUT to Store: " + topic + " in " +
                  (System.nanoTime() - startTimeNs) + " ns at " + System.currentTimeMillis());
        }
        return valueLen;

      case DELETE:
        if (logger.isTraceEnabled()) {
          startTimeNs = System.nanoTime();
        }

        storageEngine.delete(partition, keyBytes);

        if (logger.isTraceEnabled()) {
          logger.trace(consumerTaskId + " : Completed DELETE to Store: " + topic + " in " +
              (System.nanoTime() - startTimeNs) + " ns at " + System.currentTimeMillis());
        }
        return 0;
      default:
        throw new VeniceMessageException(
                consumerTaskId + " : Invalid/Unrecognized operation type submitted: " + kafkaValue.messageType);
    }
  }

  /**
   * Check whether the given schema id is available for current store.
   * The function will bypass the check if schema id is -1 (H2V job is still using it before we finishes t he integration with schema registry).
   * Right now, this function is maintaining a local cache for schema id of current store considering that the value schema is immutable;
   * If the schema id is not available, this function will polling until the schema appears;
   *
   * @param schemaId
   */
  private void checkValueSchemaAvail(int schemaId) {
    if (-1 == schemaId) {
      // TODO: Once Venice Client (VeniceShellClient) finish the integration with schema registry,
      // we need to remove this check here.
      return;
    }
    if (schemaId == AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion() ||
        schemaId == AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion()) {
      Optional<StoreVersionState> storeVersionState = storageMetadataService.getStoreVersionState(topic);
      if (storeVersionState.isPresent() && storeVersionState.get().chunked) {
        // We're good!
        // TODO: Record metric for chunk ingestion?
        return;
      } else {
        throw new VeniceException("Detected chunking in a store-version where chunking is NOT enabled. Will abort ingestion.");
      }
    }
    // Considering value schema is immutable for an existing store, we can cache it locally
    if (schemaIdSet.contains(schemaId)) {
      return;
    }
    boolean hasValueSchema = schemaRepo.hasValueSchema(storeNameWithoutVersionInfo, schemaId);
    while (!hasValueSchema && this.isRunning.get()) {
      // Since schema registration topic might be slower than data topic,
      // the consumer will be pending until the new schema arrives.
      // TODO: better polling policy
      // TODO: Need to add metrics to track this scenario
      // In the future, we might consider other polling policies,
      // such as throwing error after certain amount of time;
      // Or we might want to propagate our state to the Controller via the VeniceNotifier,
      // if we're stuck polling more than a certain threshold of time?
      logger.warn("Value schema id: " + schemaId + " is not available for store:" + storeNameWithoutVersionInfo);
      Utils.sleep(POLLING_SCHEMA_DELAY_MS);
      hasValueSchema = schemaRepo.hasValueSchema(storeNameWithoutVersionInfo, schemaId);
    }
    logger.info("Get value schema from zookeeper for schema id: " + schemaId + " in store: " + storeNameWithoutVersionInfo);
    if (!hasValueSchema) {
      throw new VeniceException("Value schema id: " + schemaId + " is still not available for store: "
          + storeNameWithoutVersionInfo + ", and it will stop waiting since the consumption task is being shutdown");
    }
    schemaIdSet.add(schemaId);
  }


  private synchronized void complete() {
    if (consumerActionsQueue.isEmpty()) {
      close();
    } else {
      logger.info(consumerTaskId + " consumerActionsQueue is not empty, ignoring complete() call.");
    }
  }

  /**
   * Stops the consumer task.
   */
  public synchronized void close() {
    isRunning.getAndSet(false);

    // KafkaConsumer is closed at the end of the run method.
    // The operation is executed on a single thread in run method.
    // This method signals the run method to end, which closes the
    // resources before exiting.
  }

  /**
   * A function to allow the service to get the current status of the task.
   * This would allow the service to create a new task if required.
   */
  public synchronized boolean isRunning() {
    return isRunning.get();
  }

  public String getTopic() {
    return topic;
  }

  KafkaConsumerWrapper getConsumer() {
    return this.consumer;
  }

  public void enableMetricsEmission() {
    this.emitMetrics.set(true);
  }

  public void disableMetricsEmission() {
    this.emitMetrics.set(false);
  }

  public Optional<Long> getCurrentOffset(int partitionId) {
    PartitionConsumptionState consumptionState = partitionConsumptionStateMap.get(partitionId);
    if (null == consumptionState) {
      return Optional.empty();
    }
    return Optional.of(consumptionState.getOffsetRecord().getOffset());
  }

  /**
   * To check whether the given partition is still consuming message from Kafka
   * @param partitionId
   * @return
   */
  public boolean isPartitionConsuming(int partitionId) {
    return partitionConsumptionStateMap.containsKey(partitionId);
  }

  /**
   * Since get real-time topic offset is expensive, so we will only retrieve source topic offset after the predefined
   * ttlMs
   */
  private static class CachedLatestOffsetGetter {
    private class TopicAndPartition {
      String topic;
      int partitionId;
      public TopicAndPartition(String topic, int partitionId) {
        this.topic = topic;
        this.partitionId = partitionId;
      }

      public String topic() {
        return topic;
      }
      public int partition() {
        return partitionId;
      }

      /**
       * The following {@link #equals(Object)} and {@link #hashCode()} are required to make sure
       * The following cache map: {@link #cachedLatestOffsets} works correctly.
       * Otherwise, every {@link #getOffset(String, int)} will invoke a call to Kafka and persist
       * a new entry in {@link #cachedLatestOffsets}, which is leaking memory.
       */

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof TopicAndPartition)) {
          return false;
        }
        TopicAndPartition that = (TopicAndPartition) o;
        return partitionId == that.partitionId && Objects.equals(topic, that.topic);
      }

      @Override
      public int hashCode() {
        return Objects.hash(topic, partitionId);
      }
    }
    private final TopicManager topicManager;
    private final Map<TopicAndPartition, Long> cachedLatestOffsets;


    CachedLatestOffsetGetter(TopicManager topicManager, long ttlMs) {
      this.topicManager = topicManager;

      //a concurrent hash map with native ttl support. get will return null if the value is expired
      cachedLatestOffsets = new PassiveExpiringMap<>(ttlMs, new ConcurrentHashMap<>());
    }

    long getOffset(String topic, int partitionId) {
      return cachedLatestOffsets.computeIfAbsent(new TopicAndPartition(topic, partitionId),
      tp -> topicManager.getLatestOffsetAndRetry(tp.topic(), tp.partition(), 3));
    }
  }
}
