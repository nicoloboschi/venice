package com.linkedin.venice.fastclient.meta;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.linkedin.venice.client.store.D2ServiceDiscovery;
import com.linkedin.venice.client.store.transport.D2TransportClient;
import com.linkedin.venice.client.store.transport.TransportClientResponse;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.compression.ZstdWithDictCompressor;
import com.linkedin.venice.controllerapi.D2ServiceDiscoveryResponseV2;
import com.linkedin.venice.fastclient.ClientConfig;
import com.linkedin.venice.fastclient.stats.ClusterStats;
import com.linkedin.venice.meta.QueryAction;
import com.linkedin.venice.metadata.response.MetadataResponseRecord;
import com.linkedin.venice.metadata.response.VersionProperties;
import com.linkedin.venice.serializer.SerializerDeserializerFactory;
import io.tehuti.metrics.MetricsRepository;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.testng.Assert;
import org.testng.annotations.Test;


public class RequestBasedMetadataTest {
  private static final int CURRENT_VERSION = 1;
  private static final String REPLICA_NAME = "host1";
  private static final String KEY_SCHEMA = "\"int\"";
  private static final String VALUE_SCHEMA = "\"int\"";
  private static final byte[] DICTIONARY = ZstdWithDictCompressor.buildDictionaryOnSyntheticAvroData();

  @Test
  public void testMetadata() {
    String storeName = "testStore";

    ClientConfig clientConfig = getMockClientConfig(storeName);
    D2TransportClient d2TransportClient = getMockD2TransportClient(storeName);
    D2ServiceDiscovery d2ServiceDiscovery = getMockD2ServiceDiscovery(d2TransportClient, storeName);
    RequestBasedMetadata requestBasedMetadata = new RequestBasedMetadata(clientConfig, d2TransportClient);
    requestBasedMetadata.setD2ServiceDiscovery(d2ServiceDiscovery);
    requestBasedMetadata.start();

    Assert.assertEquals(requestBasedMetadata.getStoreName(), storeName);
    Assert.assertEquals(requestBasedMetadata.getCurrentStoreVersion(), CURRENT_VERSION);
    Assert.assertEquals(requestBasedMetadata.getReplicas(CURRENT_VERSION, 0), Collections.singletonList(REPLICA_NAME));
    Assert.assertEquals(requestBasedMetadata.getKeySchema().toString(), KEY_SCHEMA);
    Assert.assertEquals(requestBasedMetadata.getValueSchema(1).toString(), VALUE_SCHEMA);
    Assert.assertEquals(requestBasedMetadata.getLatestValueSchemaId(), Integer.valueOf(1));
    Assert.assertEquals(requestBasedMetadata.getLatestValueSchema().toString(), VALUE_SCHEMA);
    Assert.assertEquals(
        requestBasedMetadata.getCompressor(CompressionStrategy.ZSTD_WITH_DICT, CURRENT_VERSION),
        getZstdVeniceCompressor(storeName));
  }

  private ClientConfig getMockClientConfig(String storeName) {
    ClientConfig clientConfig = mock(ClientConfig.class);
    ClusterStats clusterStats = new ClusterStats(new MetricsRepository(), storeName);
    doReturn(1L).when(clientConfig).getMetadataRefreshIntervalInSeconds();
    doReturn(storeName).when(clientConfig).getStoreName();
    doReturn(clusterStats).when(clientConfig).getClusterStats();
    doReturn(ClientRoutingStrategyType.LEAST_LOADED).when(clientConfig).getClientRoutingStrategyType();
    return clientConfig;
  }

  private D2TransportClient getMockD2TransportClient(String storeName) {
    D2TransportClient d2TransportClient = mock(D2TransportClient.class);

    VersionProperties versionProperties = new VersionProperties(
        CURRENT_VERSION,
        CompressionStrategy.ZSTD_WITH_DICT.getValue(),
        1,
        "com.linkedin.venice.partitioner.DefaultVenicePartitioner",
        Collections.emptyMap(),
        1);
    MetadataResponseRecord metadataResponse = new MetadataResponseRecord(
        versionProperties,
        Collections.singletonList(CURRENT_VERSION),
        Collections.singletonMap("1", KEY_SCHEMA),
        Collections.singletonMap("1", VALUE_SCHEMA),
        1,
        Collections.singletonMap("0", Collections.singletonList(REPLICA_NAME)),
        Collections.singletonMap(REPLICA_NAME, 0));

    byte[] metadataBody = SerializerDeserializerFactory.getAvroGenericSerializer(MetadataResponseRecord.SCHEMA$)
        .serialize(metadataResponse);
    TransportClientResponse transportClientMetadataResponse =
        new TransportClientResponse(0, CompressionStrategy.NO_OP, metadataBody);
    CompletableFuture<TransportClientResponse> completableMetadataFuture =
        CompletableFuture.completedFuture(transportClientMetadataResponse);

    TransportClientResponse transportClientDictionaryResponse =
        new TransportClientResponse(0, CompressionStrategy.NO_OP, DICTIONARY);
    CompletableFuture<TransportClientResponse> completableDictionaryFuture =
        CompletableFuture.completedFuture(transportClientDictionaryResponse);

    doReturn(completableMetadataFuture).when(d2TransportClient)
        .get(eq(QueryAction.METADATA.toString().toLowerCase() + "/" + storeName));
    doReturn(completableDictionaryFuture).when(d2TransportClient)
        .get(eq(QueryAction.DICTIONARY.toString().toLowerCase() + "/" + storeName + "/" + CURRENT_VERSION));

    return d2TransportClient;
  }

  private D2ServiceDiscovery getMockD2ServiceDiscovery(D2TransportClient d2TransportClient, String storeName) {
    D2ServiceDiscovery d2ServiceDiscovery = mock(D2ServiceDiscovery.class);

    D2ServiceDiscoveryResponseV2 d2ServiceDiscoveryResponse = new D2ServiceDiscoveryResponseV2();

    doReturn(d2ServiceDiscoveryResponse).when(d2ServiceDiscovery)
        .find(eq(d2TransportClient), eq(storeName), anyBoolean());

    return d2ServiceDiscovery;
  }

  private VeniceCompressor getZstdVeniceCompressor(String storeName) {
    String resourceName = storeName + "_v" + CURRENT_VERSION;

    return new CompressorFactory()
        .createVersionSpecificCompressorIfNotExist(CompressionStrategy.ZSTD_WITH_DICT, resourceName, DICTIONARY);
  }

}
