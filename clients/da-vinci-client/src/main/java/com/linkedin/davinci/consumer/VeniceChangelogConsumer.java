package com.linkedin.davinci.consumer;

import com.linkedin.venice.annotation.Experimental;
import com.linkedin.venice.pubsub.api.PubSubMessage;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;


/**
 * Venice change capture consumer to provide value change callback.
 *
 * @param <K> The Type for key
 * @param <V> The Type for value
 */
@Experimental
public interface VeniceChangelogConsumer<K, V> {
  /**
   * Subscribe a set of partitions for a store to this VeniceChangelogConsumer. The VeniceChangelogConsumer should try
   * to consume messages from all partitions that are subscribed to it.
   *
   * @param partitions the set of partition to subscribe and consume
   * @return a future which completes when the partitions are ready to be consumed data
   * @throws a VeniceException if subscribe operation failed for any of the partitions
   */
  CompletableFuture<Void> subscribe(Set<Integer> partitions);

  /**
   * Seek to the beginning of the push for a set of partitions.  This is analogous to doing a bootstrap of data for the consumer.
   * This seek will ONLY seek to the beginning of the version which is currently serving data, and the consumer will switch
   * to reading data from a new version (should one get created) once it has read up to the point in the change capture stream
   * that indicates the version swap (which can only occur after consuming all the data in the last push). This instructs
   * the consumer to consume data from the batch push.
   *
   * @param partitions the set of partitions to seek with
   * @return a future which completes when the operation has succeeded for all partitions.
   * @throws VeniceException if seek operation failed for any of the partitions, or seeking was performed on unsubscribed partitions
   */
  CompletableFuture<Void> seekToBeginningOfPush(Set<Integer> partitions);

  /**
   * Seek to the beginning of the push for subscribed partitions. See {@link #seekToBeginningOfPush(Set)} for more information.
   *
   * @return a future which completes when the partitions are ready to be consumed data
   * @throws VeniceException if seek operation failed for any of the partitions.
   */
  CompletableFuture<Void> seekToBeginningOfPush();

  /**
   * Seek to the end of the last push for a given set of partitions. This instructs the consumer to begin consuming events
   * which are transmitted to Venice following the last batch push.
   *
   * @param partitions the set of partitions to seek with
   * @return a future which completes when the operation has succeeded for all partitions.
   * @throws VeniceException if seek operation failed for any of the partitions, or seeking was performed on unsubscribed partitions
   */
  CompletableFuture<Void> seekToEndOfPush(Set<Integer> partitions);

  /**
   * Seek to the end of the push for all subscribed partitions. See {@link #seekToEndOfPush(Set)} for more information.
   *
   * @return a future which completes when the operation has succeeded for all partitions.
   * @throws VeniceException if seek operation failed for any of the partitions.
   */
  CompletableFuture<Void> seekToEndOfPush();

  /**
   * Seek to the end of events which have been transmitted to Venice and start consuming new events. This will ONLY
   * consume events transmitted via nearline and incremental push. It will not read batch push data.
   *
   * @param partitions the set of partitions to seek with
   * @return a future which completes when the operation has succeeded for all partitions.
   * @throws VeniceException if seek operation failed for any of the partitions, or seeking was performed on unsubscribed partitions
   */
  CompletableFuture<Void> seekToTail(Set<Integer> partitions);

  /**
   * Seek to the end of events which have been transmitted to Venice for all subscribed partitions. See {@link #seekToTail(Set)} for more information.
   *
   * @return a future which completes when the operation has succeeded for all partitions.
   * @throws VeniceException if seek operation failed for any of the partitions.
   */
  CompletableFuture<Void> seekToTail();

  /**
   * Seek the provided checkpoints for the specified partitions.
   *
   * Note about checkpoints:
   *
   * Checkpoints have the following properties and should be considered:
   *    -Checkpoints are NOT comparable or valid across partitions.
   *    -Checkpoints are NOT comparable or valid across colos
   *    -Checkpoints are NOT comparable across store versions
   *    -It is not possible to determine the number of events between two checkpoints
   *    -It is possible that a checkpoint is no longer on retention. In such case, we will return an exception to the caller.
   * @param checkpoints
   * @return a future which completes when seek has completed for all partitions
   * @throws VeniceException if seek operation failed for any of the partitions
   */
  CompletableFuture<Void> seekToCheckpoint(Set<VeniceChangeCoordinate> checkpoints);

  /**
   * Subscribe all partitions belonging to a specific store.
   *
   * @return a future which completes when all partitions are ready to be consumed data
   * @throws a VeniceException if subscribe operation failed for any of the partitions
   */
  CompletableFuture<Void> subscribeAll();

  /**
   * Stop ingesting messages from a set of partitions for a specific store.
   *
   * @param partitions The set of topic partitions to unsubscribe
   * @throws a VeniceException if unsubscribe operation failed for any of the partitions
   */
  void unsubscribe(Set<Integer> partitions);

  /**
   * Stop ingesting messages from all partitions.
   *
   * @throws a VeniceException if unsubscribe operation failed for any of the partitions
   */
  void unsubscribeAll();

  /**
   * Polling function to get any available messages from the underlying system for all partitions subscribed.
   *
   * @param timeoutInMs The maximum time to block/wait in between two polling requests (must not be greater than
   *        {@link Long#MAX_VALUE} milliseconds)
   * @return a collection of messages since the last fetch for the subscribed list of topic partitions
   * @throws a VeniceException if polling operation fails
   */
  Collection<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> poll(long timeoutInMs);

  /**
   * Release the internal resources.
   */
  void close();
}
