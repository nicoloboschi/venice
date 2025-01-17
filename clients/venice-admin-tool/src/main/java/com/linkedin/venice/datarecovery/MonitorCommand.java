package com.linkedin.venice.datarecovery;

import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.JobStatusQueryResponse;
import com.linkedin.venice.controllerapi.MultiStoreStatusResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreInfo;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.security.SSLFactory;
import java.util.Optional;


public class MonitorCommand extends Command {
  private MonitorCommand.Params params;
  private final MonitorCommand.Result result = new MonitorCommand.Result();

  // For unit test only.
  public MonitorCommand() {
  }

  public MonitorCommand(MonitorCommand.Params params) {
    this.params = params;
  }

  // For unit test only.
  public void setParams(MonitorCommand.Params params) {
    this.params = params;
  }

  @Override
  public MonitorCommand.Result getResult() {
    return result;
  }

  @Override
  public boolean needWaitForFirstTaskToComplete() {
    return false;
  }

  @Override
  public void execute() {
    String storeName = params.store;

    // Find out cluster name.
    String clusterName = params.pCtrlCliWithoutCluster.discoverCluster(storeName).getCluster();

    // Create a new controller client with cluster name specified.
    try (ControllerClient parentCtrlCli = buildControllerClient(clusterName, params.parentUrl, params.sslFactory)) {
      StoreResponse storeResponse = parentCtrlCli.getStore(storeName);
      if (storeResponse.isError()) {
        completeCoreWorkWithError(storeResponse.getError());
        return;
      }

      result.setStoreInfo(storeResponse.getStore());
      /*
       * Find out store future version. A store has a meaningful future version only when there is an ongoing
       * offline push for the store.
       */
      MultiStoreStatusResponse response = parentCtrlCli.getFutureVersions(clusterName, storeName);

      if (!response.getStoreStatusMap().containsKey(params.targetRegion)) {
        completeCoreWorkWithError(String.format("No status for region: %s", params.targetRegion));
        return;
      }

      int futureVersion = Integer.parseInt(response.getStoreStatusMap().get(params.targetRegion));
      if (futureVersion == Store.NON_EXISTING_VERSION) {
        completeCoreWorkWithMessage("No ongoing offline pushes");
        return;
      }

      String kafkaTopic = Version.composeKafkaTopic(storeName, futureVersion);
      result.setFutureVersion(futureVersion);
      result.setKafKaTopic(kafkaTopic);

      // Query job status.
      JobStatusQueryResponse jobStatusQueryResponse =
          parentCtrlCli.queryDetailedJobStatus(kafkaTopic, params.targetRegion);

      if (jobStatusQueryResponse.isError()
          || jobStatusQueryResponse.getStatus().equalsIgnoreCase(ExecutionStatus.ERROR.toString())) {
        completeCoreWorkWithError(jobStatusQueryResponse.getStatusDetails());
        return;
      }

      if (jobStatusQueryResponse.getStatus().equalsIgnoreCase(ExecutionStatus.COMPLETED.toString())) {
        completeCoreWorkWithMessage(
            String.format(
                "ver: %d, status: %s",
                jobStatusQueryResponse.getVersion(),
                jobStatusQueryResponse.getStatus()));
        return;
      }
      // For other cases, report current status.
      result.setMessage(createReportMessage(jobStatusQueryResponse));
    }
  }

  // A placeholder function for future improvement.
  private String createReportMessage(JobStatusQueryResponse resp) {
    return String.format(
        "ver: %d, status: %s, uncompleted ptn: %d/%d",
        resp.getVersion(),
        resp.getStatus(),
        resp.getUncompletedPartitions().size(),
        result.storeInfo.getPartitionCount());
  }

  private void completeCoreWorkWithError(String error) {
    result.setError(error);
    result.setCoreWorkDone(true);
  }

  private void completeCoreWorkWithMessage(String message) {
    result.setMessage(message);
    result.setCoreWorkDone(true);
  }

  public ControllerClient buildControllerClient(
      String clusterName,
      String discoveryUrls,
      Optional<SSLFactory> sslFactory) {
    return new ControllerClient(clusterName, discoveryUrls, sslFactory);
  }

  public static class Params extends Command.Params {
    // Target region.
    private String targetRegion;
    private ControllerClient pCtrlCliWithoutCluster;
    private String parentUrl;
    private Optional<SSLFactory> sslFactory;

    public void setTargetRegion(String fabric) {
      this.targetRegion = fabric;
    }

    public void setParentUrl(String parentUrl) {
      this.parentUrl = parentUrl;
    }

    public void setPCtrlCliWithoutCluster(ControllerClient parentCtrlCli) {
      this.pCtrlCliWithoutCluster = parentCtrlCli;
    }

    public void setSslFactory(Optional<SSLFactory> sslFactory) {
      this.sslFactory = sslFactory;
    }
  }

  public static class Result extends Command.Result {
    private int futureVersion = Store.NON_EXISTING_VERSION;
    private String kafKaTopic;
    private StoreInfo storeInfo;

    public void setFutureVersion(int futureVersion) {
      this.futureVersion = futureVersion;
    }

    public void setKafKaTopic(String kafKaTopic) {
      this.kafKaTopic = kafKaTopic;
    }

    public int getFutureVersion() {
      return futureVersion;
    }

    public String getKafKaTopic() {
      return kafKaTopic;
    }

    public void setStoreInfo(StoreInfo storeInfo) {
      this.storeInfo = storeInfo;
    }
  }
}
