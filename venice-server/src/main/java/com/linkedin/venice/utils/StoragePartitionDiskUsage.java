package com.linkedin.venice.utils;

import com.linkedin.venice.store.AbstractStorageEngine;
import java.util.concurrent.TimeUnit;


/**
 * This class maintains in-memory partition usage.
 * Triggered by size and/or time by #getUsage(), it will sync up with real disk usage.
 */
public class StoragePartitionDiskUsage {

  private static final long diskUsageThreshold = 32 * 1024 * 1024; // 32MB
  private static final long timeLagToSyncThreshold = 5; // 5min

  private final int partition;
  private final AbstractStorageEngine storageEngine;

  /**
   * This field indicates in memory partition usage since last syncing up with disk
   */
  private long inMemoryPartitionUsage;
  private long diskPartitionUsage;
  private long prevSyncUpTs;

  public StoragePartitionDiskUsage(int partition, AbstractStorageEngine storageEngine) {
    this.partition = partition;
    this.storageEngine = storageEngine;
    this.prevSyncUpTs = System.currentTimeMillis();
    this.syncWithDB();
  }

  /**
   * Adds a usage size to the partition
   * @param recordSize
   * @return true if recordSize >= 0
   */
  public boolean add(int recordSize) {
    if (recordSize < 0) {
      return false;
    }
    this.inMemoryPartitionUsage += recordSize;
    return true;
  }

  /**
   * When you want to check usage for a partition, use this method.
   * It will query syncing with real DB at a calculated frequency based on time and/or size trigger.
   * Generally calling this method should be quick
   * @return the disk usage for this partition
   */
  public long getUsage() {
    long currentTs = System.currentTimeMillis();
    long minutesLag = TimeUnit.MILLISECONDS.toMinutes(currentTs - prevSyncUpTs);
    if (inMemoryPartitionUsage > diskUsageThreshold || minutesLag > timeLagToSyncThreshold) {
      syncWithDB();
      prevSyncUpTs = currentTs;
      return this.diskPartitionUsage;
    } else {
      return this.diskPartitionUsage + this.inMemoryPartitionUsage;
    }
  }

  /**
   * sync with real partition DB usage and reset in memory partition usage to be zero
   * @return
   */
  private boolean syncWithDB() {
    this.diskPartitionUsage = storageEngine.getPartitionSizeInBytes(this.partition);
    this.inMemoryPartitionUsage = 0;
    return true;
  }

  // for test purpose
  protected void setPrevSyncUpTs(long millis) {
    this.prevSyncUpTs = millis;
  }

  // for test purpose
  protected long getPersistedOnlyPartitionUsage() {
    return diskPartitionUsage;
  }

  // for test purpose
  protected long getInMemoryOnlyPartitionUsage() {
    return inMemoryPartitionUsage;
  }
}