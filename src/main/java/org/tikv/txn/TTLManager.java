/*
 * Copyright 2020 TiKV Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.tikv.txn;

import com.google.protobuf.ByteString;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.TiSession;
import org.tikv.common.codec.KeyUtils;
import org.tikv.common.exception.GrpcException;
import org.tikv.common.exception.TiBatchWriteException;
import org.tikv.common.meta.TiTimestamp;
import org.tikv.common.region.RegionManager;
import org.tikv.common.region.TiRegion;
import org.tikv.common.region.TiStore;
import org.tikv.common.util.BackOffFunction;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ConcreteBackOffer;
import org.tikv.common.util.Pair;
import org.tikv.txn.type.ClientRPCResult;

/**
 * see funcionts `keepAlive` `close` `sendTxnHeartBeat` in
 * https://github.com/pingcap/tidb/blob/master/store/tikv/2pc.go
 */
public class TTLManager {
  /** 20 seconds */
  public static final int MANAGED_LOCK_TTL = 20000;

  private static final Logger LOG = LoggerFactory.getLogger(TTLManager.class);
  /** status */
  private static final int STATE_UNINITIALIZED = 0;

  private static final int STATE_RUNNING = 1;
  private static final int STATE_CLOSED = 2;
  /** 10 seconds */
  private static final int SCHEDULER_PERIOD = MANAGED_LOCK_TTL / 2;
  /** 5 seconds */
  private static final int SCHEDULER_INITIAL_DELAY = MANAGED_LOCK_TTL / 4;

  private final long startTS;
  private final ByteString primaryLock;
  private final TxnKVClient kvClient;
  private final RegionManager regionManager;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger state;

  public TTLManager(TiSession session, long startTS, byte[] primaryKey) {
    this.startTS = startTS;
    this.primaryLock = ByteString.copyFrom(primaryKey);
    this.state = new AtomicInteger(STATE_UNINITIALIZED);

    this.kvClient = session.createTxnClient();
    this.regionManager = kvClient.getRegionManager();

    scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            new BasicThreadFactory.Builder()
                .namingPattern("ttl-manager-pool-%d")
                .daemon(false)
                .build());
  }

  public void keepAlive() {
    if (state.compareAndSet(STATE_UNINITIALIZED, STATE_RUNNING)) {
      scheduler.scheduleAtFixedRate(
          this::doKeepAlive, SCHEDULER_INITIAL_DELAY, SCHEDULER_PERIOD, TimeUnit.MILLISECONDS);
    } else {
      LOG.warn("keepAlive failed state={} key={}", state.get(), KeyUtils.formatBytes(primaryLock));
    }
  }

  private void doKeepAlive() {
    BackOffer bo = ConcreteBackOffer.newCustomBackOff(MANAGED_LOCK_TTL);
    long uptime = kvClient.getTimestamp().getPhysical() - TiTimestamp.extractPhysical(startTS);
    long ttl = uptime + MANAGED_LOCK_TTL;

    LOG.info("doKeepAlive key={} uptime={} ttl={}", KeyUtils.formatBytes(primaryLock), uptime, ttl);

    try {
      sendTxnHeartBeat(bo, ttl);
      LOG.info("doKeepAlive success");
    } catch (Exception e) {
      LOG.warn("doKeepAlive error", e);
    }
  }

  private void sendTxnHeartBeat(BackOffer bo, long ttl) {
    Pair<TiRegion, TiStore> pair = regionManager.getRegionStorePairByKey(primaryLock);
    TiRegion tiRegion = pair.first;
    TiStore store = pair.second;

    ClientRPCResult result = kvClient.txnHeartBeat(bo, primaryLock, startTS, ttl, tiRegion, store);

    if (!result.isSuccess() && !result.isRetry()) {
      throw new TiBatchWriteException("sendTxnHeartBeat error", result.getException());
    }
    if (result.isRetry()) {
      try {
        bo.doBackOff(
            BackOffFunction.BackOffFuncType.BoRegionMiss,
            new GrpcException(
                String.format("sendTxnHeartBeat failed, regionId=%s", tiRegion.getId()),
                result.getException()));
        this.regionManager.invalidateStore(store.getStore().getId());
        this.regionManager.invalidateRegion(tiRegion);
        // re-split keys and commit again.
        sendTxnHeartBeat(bo, ttl);
      } catch (GrpcException e) {
        String errorMsg =
            String.format(
                "sendTxnHeartBeat error, regionId=%s, detail=%s", tiRegion.getId(), e.getMessage());
        throw new TiBatchWriteException(errorMsg, e);
      }
    }

    LOG.debug(
        "sendTxnHeartBeat success key={} ttl={} success", KeyUtils.formatBytes(primaryLock), ttl);
  }

  public void close() throws InterruptedException {
    if (state.compareAndSet(STATE_RUNNING, STATE_CLOSED)) {
      scheduler.shutdown();
    }
  }
}
