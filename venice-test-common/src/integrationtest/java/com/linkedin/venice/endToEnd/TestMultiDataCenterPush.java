package com.linkedin.venice.endToEnd;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.common.VeniceSystemStoreType;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controller.Admin;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.JobStatusQueryResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.ErrorType;
import com.linkedin.venice.exceptions.ExceptionType;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.VenicePushJob;
import com.linkedin.venice.integration.utils.MirrorMakerWrapper;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceTwoLayerMultiColoMultiClusterWrapper;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.meta.IncrementalPushPolicy;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.pushmonitor.StatusSnapshot;
import com.linkedin.venice.serialization.avro.VeniceAvroKafkaSerializer;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.TestPushUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.avro.Schema;
import org.apache.avro.util.Utf8;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.venice.hadoop.VenicePushJob.*;
import static com.linkedin.venice.utils.TestPushUtils.*;
import static org.testng.Assert.*;


public class TestMultiDataCenterPush {
  private static final Logger LOGGER = LogManager.getLogger(TestMultiDataCenterPush.class);
  private static final int TEST_TIMEOUT = 360 * Time.MS_PER_SECOND;
  private static final int NUMBER_OF_CHILD_DATACENTERS = 2;
  private static final int NUMBER_OF_CLUSTERS = 1;
  private static final String[] CLUSTER_NAMES =
      IntStream.range(0, NUMBER_OF_CLUSTERS).mapToObj(i -> "venice-cluster" + i).toArray(String[]::new); // ["venice-cluster0", "venice-cluster1", ...];

  private List<VeniceMultiClusterWrapper> childClusters;
  private List<List<VeniceControllerWrapper>> childControllers;
  private List<VeniceControllerWrapper> parentControllers;
  private VeniceTwoLayerMultiColoMultiClusterWrapper multiColoMultiClusterWrapper;

  @BeforeClass
  public void setUp() {
    Properties serverProperties = new Properties();
    serverProperties.setProperty(ConfigKeys.SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1));
    multiColoMultiClusterWrapper = ServiceFactory.getVeniceTwoLayerMultiColoMultiClusterWrapper(
        NUMBER_OF_CHILD_DATACENTERS, NUMBER_OF_CLUSTERS, 1, 1, 1, 1,
        1, Optional.empty(), Optional.empty(), Optional.of(new VeniceProperties(serverProperties)), false,
        MirrorMakerWrapper.DEFAULT_TOPIC_ALLOWLIST);

    childClusters = multiColoMultiClusterWrapper.getClusters();
    childControllers = childClusters.stream()
        .map(veniceClusterWrapper -> new ArrayList<>(veniceClusterWrapper.getControllers().values()))
        .collect(Collectors.toList());
    parentControllers = multiColoMultiClusterWrapper.getParentControllers();

    LOGGER.info("parentControllers: " + parentControllers.stream()
        .map(VeniceControllerWrapper::getControllerUrl)
        .collect(Collectors.joining(", ")));

    int i = 0;
    for (VeniceMultiClusterWrapper multiClusterWrapper : childClusters) {
      LOGGER.info("childCluster" + i++ + " controllers: " + multiClusterWrapper.getControllers()
          .values()
          .stream()
          .map(VeniceControllerWrapper::getControllerUrl)
          .collect(Collectors.joining(", ")));
    }
  }

  @AfterClass
  public void cleanUp() {
    multiColoMultiClusterWrapper.close();
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testMultiDataCenterPush() throws Exception {
    String clusterName = CLUSTER_NAMES[0];
    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
    String inputDirPath = "file:" + inputDir.getAbsolutePath();
    String storeName = Utils.getUniqueString("test_store");
    VeniceControllerWrapper leaderController = multiColoMultiClusterWrapper.getLeaderParentControllerWithRetries(clusterName);
    Properties props = defaultH2VProps(leaderController.getControllerUrl(), inputDirPath, storeName);
    createStoreForJob(clusterName, recordSchema, props).close();
    //makeSureSystemStoreIsPushed(clusterName, storeName);

    try (VenicePushJob job = new VenicePushJob("Test push job", props)) {
      job.run();
      // Verify job properties
      Assert.assertEquals(job.getKafkaTopic(), Version.composeKafkaTopic(storeName, 1));
      for (int version : leaderController.getVeniceAdmin()
          .getCurrentVersionsForMultiColos(clusterName, storeName)
          .values()) {
        Assert.assertEquals(version, 1);
      }
      Assert.assertEquals(job.getInputDirectory(), inputDirPath);
      String schema =
          "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"example.avro\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"age\",\"type\":\"int\"}]}";
      Assert.assertEquals(job.getFileSchemaString(), schema);
      Assert.assertEquals(job.getKeySchemaString(), STRING_SCHEMA);
      Assert.assertEquals(job.getValueSchemaString(), STRING_SCHEMA);
      Assert.assertEquals(job.getInputFileDataSize(), 3872);

      // Verify the data in Venice Store
      for (int dataCenterIndex = 0; dataCenterIndex < NUMBER_OF_CHILD_DATACENTERS; dataCenterIndex++) {
        VeniceMultiClusterWrapper veniceCluster = childClusters.get(dataCenterIndex);
        String routerUrl = veniceCluster.getClusters().get(clusterName).getRandomRouterURL();
        verifyVeniceStoreData(storeName, routerUrl, DEFAULT_USER_DATA_VALUE_PREFIX, DEFAULT_USER_DATA_RECORD_COUNT);
        try (ControllerClient controllerClient = new ControllerClient(clusterName, routerUrl)) {
          JobStatusQueryResponse jobStatus = controllerClient.queryJobStatus(job.getKafkaTopic());
          assertFalse(jobStatus.isError(), "Error in getting JobStatusResponse: " + jobStatus.getError());
          Assert.assertEquals(jobStatus.getStatus(), ExecutionStatus.COMPLETED.toString(),
              "After job is complete, status should reflect that");
          // We won't verify progress any more here since we decided to disable this feature
        }
      }
    }

    /**
     * To speed up integration test, here reuses the same test case to verify topic clean up logic.
     *
     * TODO: update service factory to allow specifying {@link com.linkedin.venice.ConfigKeys.MIN_NUMBER_OF_STORE_VERSIONS_TO_PRESERVE}
     * and {@link com.linkedin.venice.ConfigKeys.MIN_NUMBER_OF_UNUSED_KAFKA_TOPICS_TO_PRESERVE} to reduce job run times.
     */
    for (int i = 2; i <= 3; i++) {
      try (VenicePushJob job = new VenicePushJob("Test push job " + i, props)) {
        job.run();
      }
    }

    String v1Topic = storeName + "_v1";
    String v2Topic = storeName + "_v2";
    String v3Topic = storeName + "_v3";

    // Verify the topics in parent controller
    TopicManager parentTopicManager = leaderController.getVeniceAdmin().getTopicManager();
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertFalse(parentTopicManager.containsTopicAndAllPartitionsAreOnline(v1Topic), "Topic: " + v1Topic + " should be deleted after push");
      assertFalse(parentTopicManager.containsTopicAndAllPartitionsAreOnline(v2Topic), "Topic: " + v2Topic + " should be deleted after push");
      assertFalse(parentTopicManager.containsTopicAndAllPartitionsAreOnline(v3Topic), "Topic: " + v3Topic + " should be deleted after push");
    });

    // Verify the topics in child controller
    TopicManager childTopicManager = childControllers.get(0).get(0).getVeniceAdmin().getTopicManager();
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertFalse(childTopicManager.containsTopicAndAllPartitionsAreOnline(v1Topic), "Topic: " + v1Topic + " should be deleted after 3 pushes");
    });
    Assert.assertTrue(childTopicManager.containsTopicAndAllPartitionsAreOnline(v2Topic), "Topic: " + v2Topic + " should be kept after 3 pushes");
    Assert.assertTrue(childTopicManager.containsTopicAndAllPartitionsAreOnline(v3Topic), "Topic: " + v3Topic + " should be kept after 3 pushes");

    /**
     * In order to speed up integration test, reuse the multi data center cluster for hybrid store RT topic retention time testing
     */
    String hybridStoreName = Utils.getUniqueString("test_store_hybrid");
    Properties pushJobPropsForHybrid = defaultH2VProps(leaderController.getControllerUrl(), inputDirPath, hybridStoreName);
    // Create a hybrid store.
    createStoreForJob(clusterName, recordSchema, pushJobPropsForHybrid).close();
    //makeSureSystemStoreIsPushed(clusterName, hybridStoreName);
    /**
     * Set a high rewind time, higher than the default 5 days retention time.
     */
    long highRewindTimeInSecond = 30L * Time.SECONDS_PER_DAY; // Rewind time is one month.
    updateStore(clusterName, pushJobPropsForHybrid, new UpdateStoreQueryParams().setHybridRewindSeconds(highRewindTimeInSecond).setHybridOffsetLagThreshold(10));

    /**
     * A batch push for hybrid store would trigger the child fabrics to create RT topic.
     */
    TestPushUtils.runPushJob("Test push for hybrid", pushJobPropsForHybrid);

    /**
     * RT topic retention time should be longer than the rewind time.
     */
    String realTimeTopic = hybridStoreName + "_rt";
    long topicRetentionTimeInSecond = TimeUnit.MILLISECONDS.toSeconds(childTopicManager.getTopicRetention(realTimeTopic));
    Assert.assertTrue(topicRetentionTimeInSecond >= highRewindTimeInSecond);

    /**
     * Test for store deletion and recreation
     */

    // Delete the hybrid store and create a new one
    disableStore(clusterName, pushJobPropsForHybrid);
    deleteStore(clusterName, pushJobPropsForHybrid);
    createStoreForJob(clusterName, recordSchema, pushJobPropsForHybrid).close();

    // Create RT topic and a collision should not be reported
    updateStore(clusterName, pushJobPropsForHybrid, new UpdateStoreQueryParams().setHybridRewindSeconds(highRewindTimeInSecond).setHybridOffsetLagThreshold(10));

    TestPushUtils.runPushJob("Test push for hybrid 2", pushJobPropsForHybrid);
  }

  @Test (expectedExceptions = VeniceException.class, expectedExceptionsMessageRegExp = ".*Failed to create new store version.*", timeOut = TEST_TIMEOUT)
  public void testPushDirectlyToChildColo() throws IOException {
    // In multi-colo setup, the batch push to child controller should be disabled.
    String clusterName = CLUSTER_NAMES[0];
    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
    String inputDirPath = "file:" + inputDir.getAbsolutePath();
    String storeName = Utils.getUniqueString("store");
    String childControllerUrl = childControllers.get(0).get(0).getControllerUrl();
    Properties props = defaultH2VProps(childControllerUrl, inputDirPath, storeName);
    createStoreForJob(clusterName, recordSchema, props).close();

    TestPushUtils.runPushJob("Test push job", props);
  }

  @Test (dataProvider = "True-and-False", dataProviderClass = DataProviderUtils.class, timeOut = TEST_TIMEOUT)
  public void testEmptyPush(boolean toParent) {
    String clusterName = CLUSTER_NAMES[0];
    String storeName = Utils.getUniqueString("store");
    String parentControllerUrl = parentControllers.get(0).getControllerUrl();
    String childControllerUrl = childControllers.get(0).get(0).getControllerUrl();

    // Create store first
    ControllerClient controllerClientToParent = new ControllerClient(clusterName, parentControllerUrl);
    controllerClientToParent.createNewStore(storeName, "test_owner", "\"int\"", "\"int\"");

    ControllerClient controllerClient = new ControllerClient(clusterName, toParent ? parentControllerUrl : childControllerUrl);
    VersionCreationResponse response = controllerClient.emptyPush(storeName, "test_push_id", 1000);
    if (toParent) {
      assertFalse(response.isError(), "Empty push to parent colo should succeed");
    } else {
      Assert.assertTrue(response.isError(), "Empty push to child colo should be blocked");
    }
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testControllerBlocksConcurrentBatchPush() {
    String clusterName = CLUSTER_NAMES[0];
    String storeName = Utils.getUniqueString("blocksConcurrentBatchPush");
    String pushId1 = Utils.getUniqueString(storeName + "_push");
    String pushId2 = Utils.getUniqueString(storeName + "_push");
    String parentControllerUrl = parentControllers.get(0).getControllerUrl();

    // Create store first
    ControllerClient controllerClient = new ControllerClient(clusterName, parentControllerUrl);

    controllerClient.createNewStore(storeName, "test", "\"string\"", "\"string\"");

    VersionCreationResponse vcr1 =
        controllerClient.requestTopicForWrites(storeName, 1L, Version.PushType.BATCH, pushId1, false, true, false,
            Optional.empty(), Optional.empty(), Optional.empty(), false, -1);
    Assert.assertFalse(vcr1.isError());

    VersionCreationResponse vcr2 =
        controllerClient.requestTopicForWrites(storeName, 1L, Version.PushType.BATCH, pushId2, false, true, false,
            Optional.empty(), Optional.empty(), Optional.empty(), false, -1);
    Assert.assertTrue(vcr2.isError());
    Assert.assertEquals(vcr2.getErrorType(), ErrorType.CONCURRENT_BATCH_PUSH);
    Assert.assertEquals(vcr2.getExceptionType(), ExceptionType.BAD_REQUEST);
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testMultiDataCenterIncrementalPush() throws Exception {
    String clusterName = CLUSTER_NAMES[0];
    // create a batch push job
    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
    String inputDirPath = "file:" + inputDir.getAbsolutePath();
    String storeName = Utils.getUniqueString("store");
    Properties props = defaultH2VProps(parentControllers.get(0).getControllerUrl(), inputDirPath, storeName);
    String keySchemaStr = recordSchema.getField(props.getProperty(VenicePushJob.KEY_FIELD_PROP)).schema().toString();
    String valueSchemaStr = recordSchema.getField(props.getProperty(VenicePushJob.VALUE_FIELD_PROP)).schema().toString();

    createStoreForJob(clusterName, keySchemaStr, valueSchemaStr, props, CompressionStrategy.NO_OP, false, true).close();

    TestPushUtils.runPushJob("Test push job", props);

    // create an incremental push job
    writeSimpleAvroFileWithUserSchema2(inputDir);
    props.setProperty(INCREMENTAL_PUSH, "true");

    try (VenicePushJob incrementalPushJob = new VenicePushJob("Test incremental push job", props)) {
      incrementalPushJob.run();

      Admin.OfflinePushStatusInfo offlinePushStatusInfo = parentControllers.get(0)
          .getVeniceAdmin()
          .getOffLinePushStatus(clusterName, incrementalPushJob.getTopicToMonitor(), incrementalPushJob.getIncrementalPushVersion());
      Assert.assertEquals(offlinePushStatusInfo.getExecutionStatus(), ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED);
      Assert.assertTrue(incrementalPushJob.getIncrementalPushVersion().isPresent());
      long incrementalPushJobTimeInMs = StatusSnapshot.getIncrementalPushJobTimeInMs(incrementalPushJob.getIncrementalPushVersion().get());
      Assert.assertTrue(incrementalPushJobTimeInMs > 0L);
    }
    // validate the client can read data
    for (int dataCenterIndex = 0; dataCenterIndex < NUMBER_OF_CHILD_DATACENTERS; dataCenterIndex++) {
      VeniceMultiClusterWrapper veniceCluster = childClusters.get(dataCenterIndex);
      String routerUrl = veniceCluster.getClusters().get(clusterName).getRandomRouterURL();

      try (AvroGenericStoreClient<String, Utf8> client =
          ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(routerUrl))) {
        for (int i = 1; i <= 50; ++i) {
          Utf8 expected = new Utf8("test_name_" + i);
          Utf8 actual = client.get(Integer.toString(i)).get();
          Assert.assertEquals(actual, expected);
        }

        for (int i = 51; i <= 150; ++i) {
          Utf8 expected = new Utf8("test_name_" + (i * 2));
          Utf8 actual = client.get(Integer.toString(i)).get();
          Assert.assertEquals(actual, expected);
        }
      }
    }
  }

  @Test(timeOut = TEST_TIMEOUT)
  public void testMultiDataCenterRePushWithIncrementalPush() throws Exception {
    String clusterName = CLUSTER_NAMES[0];
    File inputDir = getTempDataDirectory();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir);
    String inputDirPath = "file:" + inputDir.getAbsolutePath();
    String storeName = Utils.getUniqueString("test-re-push-store");
    long userSetHybridRewindInSeconds = Time.SECONDS_PER_DAY;
    VeniceControllerWrapper parentController = multiColoMultiClusterWrapper.getLeaderParentControllerWithRetries(clusterName);
    Properties props = defaultH2VProps(parentController.getControllerUrl(), inputDirPath, storeName);
    createStoreForJob(clusterName, recordSchema, props).close();
    VeniceWriter<String, String, byte[]> incPushToVTWriter = null;
    VeniceWriter<String, String, byte[]> incPushToRTWriter = null;
    try (ControllerClient parentControllerClient =
        new ControllerClient(clusterName, parentController.getControllerUrl())) {
      try (VenicePushJob initialPushJob = new VenicePushJob("Test re-push job initial push", props)) {
        initialPushJob.run();
      }
      // Update the store to L/F hybrid and enable INCREMENTAL_PUSH_SAME_AS_REAL_TIME.
      assertFalse(parentControllerClient.updateStore(storeName,
          new UpdateStoreQueryParams()
              .setIncrementalPushEnabled(true)
              .setIncrementalPushPolicy(IncrementalPushPolicy.INCREMENTAL_PUSH_SAME_AS_REAL_TIME)
              .setLeaderFollowerModel(true)
              .setHybridOffsetLagThreshold(1)
              .setHybridRewindSeconds(userSetHybridRewindInSeconds)).isError());
      props.setProperty(SOURCE_KAFKA, "true");
      props.setProperty(KAFKA_INPUT_BROKER_URL, multiColoMultiClusterWrapper.getParentKafkaBrokerWrapper().getAddress());
      props.setProperty(KAFKA_INPUT_MAX_RECORDS_PER_MAPPER, "5");
      props.setProperty(VeniceWriter.ENABLE_CHUNKING, "false");
      props.setProperty(ALLOW_KIF_REPUSH_FOR_INC_PUSH_FROM_VT_TO_VT, "true");
      props.setProperty(KAFKA_INPUT_TOPIC, Version.composeKafkaTopic(storeName, 1));
      try (VenicePushJob rePushJob = new VenicePushJob("Test re-push job re-push", props)) {
        rePushJob.run();
      }
      String incPushToRTVersion = System.currentTimeMillis() + "_test_inc_push_to_rt";
      incPushToRTWriter = startIncrementalPush(parentControllerClient, storeName,
          parentController.getVeniceAdmin().getVeniceWriterFactory(), incPushToRTVersion);
      final String newVersionTopic = Version.composeKafkaTopic(storeName, parentControllerClient.getStore(storeName)
          .getStore().getLargestUsedVersionNumber());
      // Incremental push shouldn't be blocked and we will complete it once the new re-push is started.
      String incValuePrefix = "inc_test_";
      int newRePushVersion = Version.parseVersionFromKafkaTopicName(newVersionTopic) + 1;
      VeniceWriter<String, String, byte[]> finalIncPushToRTWriter = incPushToRTWriter;
      CompletableFuture.runAsync(() -> {
        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
          Assert.assertEquals(parentControllerClient.getStore(storeName).getStore().getLargestUsedVersionNumber(),
              newRePushVersion);
        });
        for (int i = 1; i <= 10; i++) {
          finalIncPushToRTWriter.put(Integer.toString(i), incValuePrefix + i, 1);
        }
        finalIncPushToRTWriter.broadcastEndOfIncrementalPush(incPushToRTVersion, new HashMap<>());
      });
      // The re-push should complete and contain all the incremental push to RT data.
      props.setProperty(KAFKA_INPUT_TOPIC, newVersionTopic);
      try (VenicePushJob rePushJob = new VenicePushJob("Test re-push job re-push", props)) {
        rePushJob.run();
      }
      // Rewind should be overwritten.
      Optional<Version> latestVersion =
          parentControllerClient.getStore(storeName).getStore().getVersion(newRePushVersion);
      Assert.assertTrue(latestVersion.isPresent());
      Assert.assertEquals(latestVersion.get().getHybridStoreConfig().getRewindTimeInSeconds(),
          VenicePushJob.DEFAULT_RE_PUSH_REWIND_IN_SECONDS_OVERRIDE);
      for (int dataCenterIndex = 0; dataCenterIndex < NUMBER_OF_CHILD_DATACENTERS; dataCenterIndex++) {
        String routerUrl = childClusters.get(dataCenterIndex).getClusters().get(clusterName).getRandomRouterURL();
        verifyVeniceStoreData(storeName, routerUrl, incValuePrefix, 10);
      }
    } finally {
      if (incPushToVTWriter != null) {
        incPushToVTWriter.close();
      }
      if (incPushToRTWriter != null) {
        incPushToRTWriter.close();
      }
    }
  }

  private VeniceWriter<String, String, byte[]> startIncrementalPush(ControllerClient controllerClient, String storeName,
      VeniceWriterFactory veniceWriterFactory, String incrementalPushVersion) {
    VersionCreationResponse response = controllerClient.requestTopicForWrites(storeName, 1024,
        Version.PushType.INCREMENTAL, "test-incremental-push", true, true, false, Optional.empty(),
        Optional.empty(), Optional.empty(), false, -1);
    assertFalse(response.isError());
    Assert.assertNotNull(response.getKafkaTopic());
    VeniceWriter veniceWriter  =
        veniceWriterFactory.createVeniceWriter(response.getKafkaTopic(),
            new VeniceAvroKafkaSerializer(STRING_SCHEMA), new VeniceAvroKafkaSerializer(STRING_SCHEMA));
    veniceWriter.broadcastStartOfIncrementalPush(incrementalPushVersion, new HashMap<>());
    return veniceWriter;
  }

  private void verifyVeniceStoreData(String storeName, String routerUrl, String valuePrefix, int keyCount)
      throws ExecutionException, InterruptedException {
    try (AvroGenericStoreClient<String, Object> client = ClientFactory.getAndStartGenericAvroClient(
        ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(routerUrl))) {
      for (int i = 1; i <= keyCount; ++i) {
        String expected = valuePrefix + i;
        Object actual = client.get(Integer.toString(i)).get(); /* client.get().get() returns a Utf8 object */
        Assert.assertNotNull(actual, "Unexpected null value for key: " + i);
        Assert.assertEquals(actual.toString(), expected);
      }
    }
  }

  private void makeSureSystemStoreIsPushed(String clusterName, String storeName) {
    TestUtils.waitForNonDeterministicAssertion(1, TimeUnit.MINUTES, true, () -> {
      for (List<VeniceControllerWrapper> childController : childControllers) {
        ControllerClient controllerClient =
            new ControllerClient(clusterName, childController.get(0).getControllerUrl());
        StoreResponse storeResponse =
            controllerClient.getStore(VeniceSystemStoreType.META_STORE.getSystemStoreName(storeName));
        Assert.assertFalse(storeResponse.isError());
        Assert.assertTrue(storeResponse.getStore().getCurrentVersion() > 0);

        StoreResponse storeResponse2 =
            controllerClient.getStore(VeniceSystemStoreType.DAVINCI_PUSH_STATUS_STORE.getSystemStoreName(storeName));
        Assert.assertFalse(storeResponse2.isError());
        Assert.assertTrue(storeResponse2.getStore().getCurrentVersion() > 0);
      }
    });
  }
}
