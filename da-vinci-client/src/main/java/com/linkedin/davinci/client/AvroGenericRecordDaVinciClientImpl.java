package com.linkedin.davinci.client;

import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.schema.SchemaReader;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.client.store.D2ServiceDiscovery;
import com.linkedin.venice.client.store.transport.D2TransportClient;
import com.linkedin.venice.client.store.transport.TransportClient;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.config.VeniceClusterConfig;
import com.linkedin.venice.controller.init.SystemSchemaInitializationRoutine;
import com.linkedin.venice.controllerapi.D2ServiceDiscoveryResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.HelixReadOnlySchemaRepository;
import com.linkedin.venice.helix.HelixReadOnlyStoreRepository;
import com.linkedin.venice.helix.ZkClientFactory;
import com.linkedin.venice.kafka.consumer.KafkaStoreIngestionService;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serializer.RecordSerializer;
import com.linkedin.venice.serializer.SerializerDeserializerFactory;
import com.linkedin.venice.server.VeniceConfigLoader;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.stats.AggVersionedBdbStorageEngineStats;
import com.linkedin.venice.stats.AggVersionedStorageEngineStats;
import com.linkedin.venice.stats.TehutiUtils;
import com.linkedin.venice.stats.ZkClientStatusStats;
import com.linkedin.venice.storage.BdbStorageMetadataService;
import com.linkedin.venice.storage.StorageService;
import com.linkedin.venice.storage.chunking.ComputeChunkingAdapter;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.utils.Utils;
import io.tehuti.metrics.MetricsRepository;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.log4j.Logger;


public class AvroGenericRecordDaVinciClientImpl<K> implements DaVinciClient<K, GenericRecord> {
  private static String DAVINCI_CLIENT_NAME = "davinci_client";
  private static final byte[] BINARY_DECODER_PARAM = new byte[16];
  private static int REFRESH_ATTEMPTS_FOR_ZK_RECONNECT = 1;
  private static int REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS = 1;
  private static final Logger logger = Logger.getLogger(AvroGenericRecordDaVinciClientImpl.class);

  private final BinaryDecoder binaryDecoder = DecoderFactory.get().binaryDecoder(BINARY_DECODER_PARAM, null);
  private final String storeName;
  private final boolean useFastAvro;
  private final MetricsRepository metricsRepository;
  private final VeniceConfigLoader veniceConfigLoader;
  private final DaVinciConfig daVinciConfig;
  private final ClientConfig clientConfig;
  private final D2ServiceDiscovery d2ServiceDiscovery = new D2ServiceDiscovery();
  private final List<AbstractVeniceService> services = new ArrayList<>();
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private ZkClient zkClient;
  private ReadOnlyStoreRepository metadataReposotory;
  private ReadOnlySchemaRepository schemaRepository;
  private StorageService storageService;
  private BdbStorageMetadataService storageMetadataService;
  private KafkaStoreIngestionService kafkaStoreIngestionService;
  private RecordSerializer<K> keySerializer;
  private DaVinciVersionFinder daVinciVersionFinder;
  private D2TransportClient d2TransportClient;

  public AvroGenericRecordDaVinciClientImpl(
      VeniceConfigLoader veniceConfigLoader,
      DaVinciConfig daVinciConfig,
      ClientConfig clientConfig) {
    this.veniceConfigLoader = veniceConfigLoader;
    this.daVinciConfig = daVinciConfig;
    this.clientConfig = clientConfig;
    this.storeName = clientConfig.getStoreName();
    this.useFastAvro = clientConfig.isUseFastAvro();
    this.metricsRepository = Optional.ofNullable(clientConfig.getMetricsRepository())
        .orElse(TehutiUtils.getMetricsRepository(DAVINCI_CLIENT_NAME));
  }

  @Override
  public CompletableFuture<Void> subscribeToAllPartitions() {
    return CompletableFuture.allOf();
  }

  @Override
  public CompletableFuture<Void> subscribe(Set<Integer> partitions) {
    return CompletableFuture.allOf();
  }

  @Override
  public CompletableFuture<Void> unsubscribe(Set<Integer> partitions) {
    return CompletableFuture.allOf();
  }

  @Override
  public CompletableFuture<GenericRecord> get(K key) throws VeniceClientException {
    if (!isStarted()) {
      throw new VeniceClientException("Client is not started.");
    }
    ByteBuffer keyBuffer = ByteBuffer.wrap(keySerializer.serialize(key));
    int partition = getPartition(key);
    int version = daVinciVersionFinder.getLatestVersion(partition);
    if (version == Store.NON_EXISTING_VERSION) {
      throw new VeniceClientException("Failed to find a ready store version.");
    }
    String topic = Version.composeKafkaTopic(getStoreName(), version);
    AbstractStorageEngine store = storageService.getStorageEngineRepository().getLocalStorageEngine(topic);
    if (store == null) {
      throw new VeniceClientException("Failed to find a ready store version.");
    }
    boolean isChunked = kafkaStoreIngestionService.isStoreVersionChunked(topic);
    CompressionStrategy compressionStrategy = kafkaStoreIngestionService.getStoreVersionCompressionStrategy(topic);
    Schema latestValueSchema = getLatestValueSchema();
    GenericRecord valueRecord = new GenericData.Record(latestValueSchema);
    valueRecord =
        ComputeChunkingAdapter.get(store, partition, keyBuffer, isChunked, valueRecord, binaryDecoder, null, compressionStrategy, useFastAvro, schemaRepository, getStoreName());
    return CompletableFuture.completedFuture(valueRecord);
  }

  private int getPartition(K key) {
    return 0;
  }

  @Override
  public CompletableFuture<Map<K, GenericRecord>> batchGet(Set<K> keys) throws VeniceClientException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void start() throws VeniceClientException {
    boolean isntStarted = isStarted.compareAndSet(false, true);
    if (!isntStarted) {
      throw new VeniceClientException("Client is already started!");
    }
    TransportClient transportClient = ClientFactory.getTransportClient(clientConfig);
    if (!(transportClient instanceof D2TransportClient)) {
      throw new VeniceClientException("Da Vinci only supports D2 client.");
    }
    this.d2TransportClient = (D2TransportClient) transportClient;
    D2ServiceDiscoveryResponse d2ServiceDiscoveryResponse = d2ServiceDiscovery.discoverD2Service(d2TransportClient, getStoreName());
    d2TransportClient.setServiceName(d2ServiceDiscoveryResponse.getD2Service());
    VeniceClusterConfig clusterConfig = veniceConfigLoader.getVeniceClusterConfig();
    zkClient = ZkClientFactory.newZkClient(clusterConfig.getZookeeperAddress());
    zkClient.subscribeStateChanges(new ZkClientStatusStats(metricsRepository, "davinci-zk-client"));
    HelixAdapterSerializer adapter = new HelixAdapterSerializer();
    metadataReposotory = new HelixReadOnlyStoreRepository(zkClient, adapter, d2ServiceDiscoveryResponse.getCluster(),
        REFRESH_ATTEMPTS_FOR_ZK_RECONNECT, REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS);
    metadataReposotory.refresh();
    schemaRepository = new HelixReadOnlySchemaRepository(metadataReposotory, zkClient, adapter, d2ServiceDiscoveryResponse.getCluster(),
        REFRESH_ATTEMPTS_FOR_ZK_RECONNECT, REFRESH_INTERVAL_FOR_ZK_RECONNECT_IN_MS);
    schemaRepository.refresh();
    storageMetadataService = new BdbStorageMetadataService(clusterConfig);
    services.add(storageMetadataService);
    AggVersionedBdbStorageEngineStats
        bdbStorageEngineStats = new AggVersionedBdbStorageEngineStats(metricsRepository, metadataReposotory);
    AggVersionedStorageEngineStats
        storageEngineStats = new AggVersionedStorageEngineStats(metricsRepository, metadataReposotory);
    // create and add StorageService. storeRepository will be populated by StorageService,
    storageService = new StorageService(veniceConfigLoader, s -> storageMetadataService.clearStoreVersionState(s),
        bdbStorageEngineStats, storageEngineStats);
    services.add(storageService);
    // SchemaReader of Kafka protocol
    SchemaReader schemaReader = ClientFactory.getSchemaReader(
        ClientConfig.cloneConfig(clientConfig).setStoreName(SystemSchemaInitializationRoutine.getSystemStoreName(AvroProtocolDefinition.KAFKA_MESSAGE_ENVELOPE)));
    kafkaStoreIngestionService = new KafkaStoreIngestionService(
        storageService.getStorageEngineRepository(),
        veniceConfigLoader,
        storageMetadataService,
        metadataReposotory,
        schemaRepository,
        metricsRepository,
        Optional.of(schemaReader),
        Optional.of(clientConfig));
    services.add(kafkaStoreIngestionService);
    this.keySerializer =
        SerializerDeserializerFactory.getAvroGenericSerializer(getKeySchema());
    // TODO: initiate ingestion service. pass in ingestionService as null to make it compile.
    this.daVinciVersionFinder = new DaVinciVersionFinder(
        getStoreName(), metadataReposotory, null, storageService.getStorageEngineRepository()
    );
    logger.info("Starting " + services.size() + " services.");
    long start = System.currentTimeMillis();
    for (AbstractVeniceService service : services) {
      service.start();
    }
    long end = System.currentTimeMillis();
    logger.info("Startup completed in " + (end - start) + " ms.");
  }

  @Override
  public void close() {
    List<Exception> exceptions = new ArrayList<>();
    logger.info("Stopping all services ");

    /* Stop in reverse order */
    synchronized (this) {
      if (!isStarted()) {
        logger.info("The client is already stopped, ignoring duplicate attempt.");
        return;
      }
      for (AbstractVeniceService service : Utils.reversed(services)) {
        try {
          service.stop();
        } catch (Exception e) {
          exceptions.add(e);
          logger.error("Exception in stopping service: " + service.getName(), e);
        }
      }
      logger.info("All services stopped");

      if (exceptions.size() > 0) {
        throw new VeniceException(exceptions.get(0));
      }
      isStarted.set(false);

      metricsRepository.close();
      zkClient.close();
    }
  }

  @Override
  public String getStoreName() {
    return storeName;
  }

  @Override
  public Schema getKeySchema() {
    return schemaRepository.getKeySchema(getStoreName()).getSchema();
  }

  @Override
  public Schema getLatestValueSchema() {
    return schemaRepository.getLatestValueSchema(getStoreName()).getSchema();
  }

  public boolean isUseFastAvro() {
    return useFastAvro;
  }

  /**
   * @return true if the {@link AvroGenericRecordDaVinciClientImpl} and all of its inner services are fully started
   *         false if the {@link AvroGenericRecordDaVinciClientImpl} was not started or if any of its inner services
   *         are not finished starting.
   */
  public boolean isStarted() {
    return isStarted.get() && services.stream().allMatch(abstractVeniceService -> abstractVeniceService.isStarted());
  }

}