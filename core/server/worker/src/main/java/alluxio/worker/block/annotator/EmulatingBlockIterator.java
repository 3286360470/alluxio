/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block.annotator;

import alluxio.StorageTierAssoc;
import alluxio.WorkerStorageTierAssoc;
import alluxio.collections.Pair;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.worker.block.BlockMetadataEvictorView;
import alluxio.worker.block.BlockMetadataManager;
import alluxio.worker.block.BlockStoreEventListener;
import alluxio.worker.block.BlockStoreLocation;
import alluxio.worker.block.evictor.EvictionPlan;
import alluxio.worker.block.evictor.Evictor;
import alluxio.worker.block.meta.StorageTier;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is used to support deprecated {@link Evictor} implementations.
 * It works by assuming an order in the eviction plan generated by the evictor.
 */
public class EmulatingBlockIterator implements BlockIterator {
  /** Block metadata manager. */
  private final BlockMetadataManager mMetadataManager;
  /** Evictor that is to be emulated. */
  private final Evictor mEvictor;
  /** Mapping from tier alias to space in bytes to be reserved on the tier. */
  private final Map<String, Long> mReservedSpaces = new HashMap<>();

  /**
   * Creates a block iterator that emulates an {@link Evictor}.
   *
   * @param metadataManager the metadata manager
   * @param evictor the evictor
   */
  public EmulatingBlockIterator(BlockMetadataManager metadataManager, Evictor evictor) {
    mMetadataManager = metadataManager;
    mEvictor = evictor;

    initEvictorConfiguration();
  }

  /**
   * Used to calculate configuration that evictors need to run.
   */
  private void initEvictorConfiguration() {
    StorageTierAssoc storageTierAssoc = new WorkerStorageTierAssoc();
    // Calculate tier capacities.
    Map<String, Long> tierCapacities = new HashMap<>();
    for (StorageTier tier : mMetadataManager.getTiers()) {
      tierCapacities.put(tier.getTierAlias(), tier.getCapacityBytes());
    }

    long lastTierReservedBytes = 0;
    for (int ordinal = 0; ordinal < storageTierAssoc.size(); ordinal++) {
      String tierAlias = storageTierAssoc.getAlias(ordinal);
      long tierCapacity = tierCapacities.get(tierAlias);
      // High watermark defines when to start the space reserving process.
      // It's only validated in this emulator, it doesn't trigger any background task.
      PropertyKey tierHighWatermarkProp =
          PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO.format(ordinal);
      double tierHighWatermarkConf = ServerConfiguration.getDouble(tierHighWatermarkProp);
      Preconditions.checkArgument(tierHighWatermarkConf > 0,
          "The high watermark of tier %s should be positive, but is %s", Integer.toString(ordinal),
          tierHighWatermarkConf);
      Preconditions.checkArgument(tierHighWatermarkConf < 1,
          "The high watermark of tier %s should be less than 1.0, but is %s",
          Integer.toString(ordinal), tierHighWatermarkConf);

      // Low watermark defines when to stop the space reserving process if started
      PropertyKey tierLowWatermarkProp =
          PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO.format(ordinal);
      double tierLowWatermarkConf = ServerConfiguration.getDouble(tierLowWatermarkProp);
      Preconditions.checkArgument(tierLowWatermarkConf >= 0,
          "The low watermark of tier %s should not be negative, but is %s",
          Integer.toString(ordinal), tierLowWatermarkConf);
      Preconditions.checkArgument(tierLowWatermarkConf < tierHighWatermarkConf,
          "The low watermark (%s) of tier %d should not be smaller than the high watermark (%s)",
          tierLowWatermarkConf, ordinal, tierHighWatermarkConf);
      long reservedSpace = (long) (tierCapacity - tierCapacity * tierLowWatermarkConf);
      lastTierReservedBytes += reservedSpace;
      // On each tier, we reserve no more than its capacity
      lastTierReservedBytes =
          (lastTierReservedBytes <= tierCapacity) ? lastTierReservedBytes : tierCapacity;
      mReservedSpaces.put(tierAlias, lastTierReservedBytes);
      // Update special ANY_TIER to have total reserved capacity.
      if (mReservedSpaces.containsKey(BlockStoreLocation.ANY_TIER)) {
        mReservedSpaces.put(BlockStoreLocation.ANY_TIER,
            mReservedSpaces.get(BlockStoreLocation.ANY_TIER) + lastTierReservedBytes);
      } else {
        mReservedSpaces.put(BlockStoreLocation.ANY_TIER, lastTierReservedBytes);
      }
    }
  }

  @Override
  public Iterator<Long> getIterator(BlockStoreLocation location, BlockOrder order) {
    /**
     * Invoke the evictor for the location with configured free space threshold.
     * From the generated plan, extract the order imposed by cascading eviction logic.
     *
     * This emulation assumes that toEvict() and toMove() lists of the plan
     * is populated based on an internal order maintained by the evictor implementation.
     */

    // Generate the plan as every block being evictable.
    EvictionPlan evictionPlan =
        mEvictor.freeSpaceWithView(mReservedSpaces.get(location.tierAlias()), location,
            new BlockMetadataEvictorView(mMetadataManager, Collections.emptySet(),
                Collections.emptySet()),
            Evictor.Mode.BEST_EFFORT);

    // Extract evicted blocks in order.
    List<Long> toEvict = Collections.emptyList();
    if (evictionPlan.toEvict() != null) {
      toEvict =
          evictionPlan.toEvict().stream().map((kvp) -> kvp.getFirst()).collect(Collectors.toList());
    }

    // Extract moved blocks in order.
    List<Long> toMove = Collections.emptyList();
    if (evictionPlan.toMove() != null) {
      toMove = evictionPlan.toMove().stream().map((kvp) -> kvp.getSrcBlockId())
          .collect(Collectors.toList());
    }

    // Select which list to feed as iterator.
    // For the lowest tier blocks to evict will be considered.
    // For the other cases blocks to move will be considered.
    List<Long> iteratorList = toEvict;
    if (!toMove.isEmpty()) {
      iteratorList = toMove;
    }

    // Reverse the list if requested.
    if (order == BlockOrder.REVERSE) {
      Collections.reverse(iteratorList);
    }

    // Return an iterator from combined block id list.
    return iteratorList.iterator();
  }

  @Override
  public List<Long> getIntersectionList(BlockStoreLocation srcLocation, BlockOrder srcOrder,
      BlockStoreLocation dstLocation, BlockOrder dstOrder, int intersectionWidth,
      BlockOrder intersectionOrder, Function<Long, Boolean> blockFilterFunc) {
    // Intersection calculation not possible.
    return Collections.emptyList();
  }

  @Override
  public Pair<List<Long>, List<Long>> getSwaps(BlockStoreLocation srcLocation, BlockOrder srcOrder,
      BlockStoreLocation dstLocation, BlockOrder dstOrder, int swapRange,
      BlockOrder intersectionOrder, Function<Long, Boolean> blockFilterFunc) {
    // Swap calculation not possible.
    return new Pair(Collections.emptyList(), Collections.emptyList());
  }

  @Override
  public boolean aligned(BlockStoreLocation srcLocation, BlockStoreLocation dstLocation,
                         BlockOrder order, Function<Long, Boolean> blockFilterFunc) {
    // Overlap report not possible.
    return false;
  }

  @Override
  public List<BlockStoreEventListener> getListeners() {
    if (mEvictor instanceof BlockStoreEventListener) {
      return ImmutableList.of((BlockStoreEventListener) mEvictor);
    }
    return ImmutableList.of();
  }
}
