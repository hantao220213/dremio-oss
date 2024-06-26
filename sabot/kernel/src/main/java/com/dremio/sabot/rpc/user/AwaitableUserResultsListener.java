/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.rpc.user;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.proto.UserBitShared.QueryResult.QueryState;
import com.dremio.exec.rpc.ConnectionThrottle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** General mechanism for waiting on the query to be executed */
public class AwaitableUserResultsListener implements UserResultsListener {

  private final AtomicInteger count = new AtomicInteger();
  private final CountDownLatch latch = new CountDownLatch(1);
  private volatile UserException exception;
  private final UserResultsListener child;

  /**
   * @param child the listener responsible for consuming the data
   */
  public AwaitableUserResultsListener(UserResultsListener child) {
    if (child == null) {
      throw new NullPointerException("child should not be null");
    }
    this.child = child;
  }

  @Override
  public void queryIdArrived(QueryId queryId) {
    child.queryIdArrived(queryId);
  }

  @Override
  public void dataArrived(QueryDataBatch result, ConnectionThrottle throttle) {
    count.addAndGet(result.getHeader().getRowCount());
    child.dataArrived(result, throttle);
  }

  @Override
  public void submissionFailed(UserException ex) {
    exception = ex;
    latch.countDown();
    child.submissionFailed(ex);
  }

  @Override
  public void queryCompleted(QueryState state) {
    latch.countDown();
    child.queryCompleted(state);
  }

  public int await() throws Exception {
    latch.await();
    if (exception != null) {
      exception.addSuppressed(new RuntimeException("Exception in executor threadpool"));
      throw exception;
    }
    return count.get();
  }
}
