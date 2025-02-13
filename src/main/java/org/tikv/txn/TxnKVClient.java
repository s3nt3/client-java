/*
 * Copyright 2019 TiKV Project Authors.
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

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.ReadOnlyPDClient;
import org.tikv.common.TiConfiguration;
import org.tikv.common.exception.GrpcException;
import org.tikv.common.exception.KeyException;
import org.tikv.common.exception.RegionException;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.exception.TiKVException;
import org.tikv.common.meta.TiTimestamp;
import org.tikv.common.region.RegionManager;
import org.tikv.common.region.RegionStoreClient;
import org.tikv.common.region.TiRegion;
import org.tikv.common.region.TiStore;
import org.tikv.common.util.BackOffFunction;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ConcreteBackOffer;
import org.tikv.kvproto.Kvrpcpb;
import org.tikv.txn.type.ClientRPCResult;

/** KV client of transaction APIs for GET/PUT/DELETE/SCAN */
public class TxnKVClient implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TxnKVClient.class);

  private final RegionStoreClient.RegionStoreClientBuilder clientBuilder;
  private final TiConfiguration conf;
  private final RegionManager regionManager;
  private final ReadOnlyPDClient pdClient;

  public TxnKVClient(
      TiConfiguration conf,
      RegionStoreClient.RegionStoreClientBuilder clientBuilder,
      ReadOnlyPDClient pdClient) {
    this.conf = conf;
    this.clientBuilder = clientBuilder;
    this.regionManager = clientBuilder.getRegionManager();
    this.pdClient = pdClient;
  }

  public TiConfiguration getConf() {
    return conf;
  }

  public RegionManager getRegionManager() {
    return regionManager;
  }

  public TiTimestamp getTimestamp() {
    BackOffer bo = ConcreteBackOffer.newTsoBackOff();
    TiTimestamp timestamp = new TiTimestamp(0, 0);
    try {
      while (true) {
        try {
          timestamp = pdClient.getTimestamp(bo);
          break;
        } catch (final TiKVException e) {
          // retry is exhausted
          bo.doBackOff(BackOffFunction.BackOffFuncType.BoPDRPC, e);
        }
      }
    } catch (GrpcException e1) {
      LOG.error("Get tso from pd failed,", e1);
    }
    return timestamp;
  }

  /** when encountered region error,ErrBodyMissing, and other errors */
  public ClientRPCResult prewrite(
      BackOffer backOffer,
      List<Kvrpcpb.Mutation> mutations,
      ByteString primary,
      long lockTTL,
      long startTs,
      TiRegion tiRegion,
      TiStore store) {
    ClientRPCResult result = new ClientRPCResult(true, false, null);
    // send request
    RegionStoreClient client = clientBuilder.build(tiRegion, store);
    try {
      client.prewrite(backOffer, primary, mutations, startTs, lockTTL);
    } catch (Exception e) {
      result.setSuccess(false);
      // mark retryable, region error, should retry prewrite again
      result.setRetry(retryableException(e));
      result.setException(e);
    }
    return result;
  }

  /** TXN Heart Beat: update primary key ttl */
  public ClientRPCResult txnHeartBeat(
      BackOffer backOffer,
      ByteString primaryLock,
      long startTs,
      long ttl,
      TiRegion tiRegion,
      TiStore store) {
    ClientRPCResult result = new ClientRPCResult(true, false, null);
    // send request
    RegionStoreClient client = clientBuilder.build(tiRegion, store);
    try {
      client.txnHeartBeat(backOffer, primaryLock, startTs, ttl);
    } catch (Exception e) {
      result.setSuccess(false);
      // mark retryable, region error, should retry heart beat again
      result.setRetry(retryableException(e));
      result.setException(e);
    }
    return result;
  }

  /**
   * Commit request of 2pc, add backoff logic when encountered region error, ErrBodyMissing, and
   * other errors
   *
   * @param backOffer
   * @param keys
   * @param startTs
   * @param commitTs
   * @param tiRegion
   * @return
   */
  public ClientRPCResult commit(
      BackOffer backOffer,
      ByteString[] keys,
      long startTs,
      long commitTs,
      TiRegion tiRegion,
      TiStore store) {
    ClientRPCResult result = new ClientRPCResult(true, false, null);
    // send request
    RegionStoreClient client = clientBuilder.build(tiRegion, store);
    List<ByteString> byteList = Lists.newArrayList();
    byteList.addAll(Arrays.asList(keys));
    try {
      client.commit(backOffer, byteList, startTs, commitTs);
    } catch (Exception e) {
      result.setSuccess(false);
      // mark retryable, region error, should retry prewrite again
      result.setRetry(retryableException(e));
      result.setException(e);
    }
    return result;
  }

  // According to TiDB's implementation, when it comes to rpc error
  // commit status remains undecided.
  // If we fail to receive response for the request that commits primary key, it will be
  // undetermined whether this
  // transaction has been successfully committed.
  // Under this circumstance,  we can not declare the commit is complete (may lead to data lost),
  // nor can we throw
  // an error (may lead to the duplicated key error when upper level restarts the transaction).
  // Currently the best
  // solution is to populate this error and let upper layer drop the connection to the corresponding
  // mysql client.
  // TODO: check this logic to see are we satisfied?
  private boolean retryableException(Exception e) {
    return e instanceof TiClientInternalException
        || e instanceof KeyException
        || e instanceof RegionException
        || e instanceof StatusRuntimeException;
  }

  @Override
  public void close() throws Exception {}
}
