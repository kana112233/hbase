/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.quotas;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Noop quota limiter returned when no limiter is associated to the user/table
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
class NoopQuotaLimiter implements QuotaLimiter {
  private static QuotaLimiter instance = new NoopQuotaLimiter();

  private NoopQuotaLimiter() {
    // no-op
  }

  @Override
  public void checkQuota(long writeReqs, long estimateWriteSize, long readReqs,
    long estimateReadSize, long estimateWriteCapacityUnit, long estimateReadCapacityUnit,
    boolean isAtomic, long estimateHandlerThreadUsageMs) throws RpcThrottlingException {
    // no-op
  }

  @Override
  public void grabQuota(long writeReqs, long writeSize, long readReqs, long readSize,
    long writeCapacityUnit, long readCapacityUnit, boolean isAtomic,
    long estimateHandlerThreadUsageMs) {
    // no-op
  }

  @Override
  public void consumeWrite(final long size, long capacityUnit, boolean isAtomic) {
    // no-op
  }

  @Override
  public void consumeRead(final long size, long capacityUnit, boolean isAtomic) {
    // no-op
  }

  @Override
  public void consumeTime(final long handlerMillisUsed) {
    // no-op
  }

  @Override
  public boolean isBypass() {
    return true;
  }

  @Override
  public long getWriteAvailable() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getRequestNumLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public long getReadNumLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public long getWriteNumLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public long getReadAvailable() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getReadLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public long getWriteLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public String toString() {
    return "NoopQuotaLimiter";
  }

  public static QuotaLimiter get() {
    return instance;
  }
}
