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
package org.apache.hadoop.hbase.regionserver;

import static org.junit.Assert.assertEquals;

import java.util.Collection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category(MediumTests.class)
public class TestCompactSplitThread {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestCompactSplitThread.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestCompactSplitThread.class);
  private static final HBaseTestingUtil TEST_UTIL = new HBaseTestingUtil();
  private final TableName tableName = TableName.valueOf(getClass().getSimpleName());
  private final byte[] family = Bytes.toBytes("f");
  private static final int NUM_RS = 1;
  private static final int blockingStoreFiles = 3;
  private static Path rootDir;
  private static FileSystem fs;

  /**
   * Setup the config for the cluster
   */
  @Before
  public void setupCluster() throws Exception {
    setupConf(TEST_UTIL.getConfiguration());
    TEST_UTIL.startMiniCluster(NUM_RS);
    fs = TEST_UTIL.getDFSCluster().getFileSystem();
    rootDir = TEST_UTIL.getMiniHBaseCluster().getMaster().getMasterFileSystem().getRootDir();

  }

  private static void setupConf(Configuration conf) {
    // disable the ui
    conf.setInt("hbase.regionsever.info.port", -1);
    // so make sure we get a compaction when doing a load, but keep around some
    // files in the store
    conf.setInt("hbase.hstore.compaction.min", 2);
    conf.setInt("hbase.hstore.compactionThreshold", 5);
    // change the flush size to a small amount, regulating number of store files
    conf.setInt("hbase.hregion.memstore.flush.size", 25000);

    // block writes if we get to blockingStoreFiles store files
    conf.setInt("hbase.hstore.blockingStoreFiles", blockingStoreFiles);
    // Ensure no extra cleaners on by default (e.g. TimeToLiveHFileCleaner)
    conf.setInt(CompactSplit.LARGE_COMPACTION_THREADS, 3);
    conf.setInt(CompactSplit.SMALL_COMPACTION_THREADS, 4);
    conf.setInt(CompactSplit.SPLIT_THREADS, 5);
  }

  @After
  public void cleanupTest() throws Exception {
    try {
      TEST_UTIL.shutdownMiniCluster();
    } catch (Exception e) {
      // NOOP;
    }
  }

  @Test
  public void testThreadPoolSizeTuning() throws Exception {
    Configuration conf = TEST_UTIL.getConfiguration();
    Connection conn = ConnectionFactory.createConnection(conf);
    try {
      TableDescriptor tableDescriptor = TableDescriptorBuilder.newBuilder(tableName)
        .setColumnFamily(ColumnFamilyDescriptorBuilder.of(family)).setCompactionEnabled(false)
        .build();
      TEST_UTIL.getAdmin().createTable(tableDescriptor);
      TEST_UTIL.waitTableAvailable(tableName);
      HRegionServer regionServer = TEST_UTIL.getRSForFirstRegionInTable(tableName);

      // check initial configuration of thread pool sizes
      assertEquals(3, regionServer.getCompactSplitThread().getLargeCompactionThreadNum());
      assertEquals(4, regionServer.getCompactSplitThread().getSmallCompactionThreadNum());
      assertEquals(5, regionServer.getCompactSplitThread().getSplitThreadNum());

      // change bigger configurations and do online update
      conf.setInt(CompactSplit.LARGE_COMPACTION_THREADS, 4);
      conf.setInt(CompactSplit.SMALL_COMPACTION_THREADS, 5);
      conf.setInt(CompactSplit.SPLIT_THREADS, 6);
      try {
        regionServer.getCompactSplitThread().onConfigurationChange(conf);
      } catch (IllegalArgumentException iae) {
        Assert.fail("Update bigger configuration failed!");
      }

      // check again after online update
      assertEquals(4, regionServer.getCompactSplitThread().getLargeCompactionThreadNum());
      assertEquals(5, regionServer.getCompactSplitThread().getSmallCompactionThreadNum());
      assertEquals(6, regionServer.getCompactSplitThread().getSplitThreadNum());

      // change smaller configurations and do online update
      conf.setInt(CompactSplit.LARGE_COMPACTION_THREADS, 2);
      conf.setInt(CompactSplit.SMALL_COMPACTION_THREADS, 3);
      conf.setInt(CompactSplit.SPLIT_THREADS, 4);
      try {
        regionServer.getCompactSplitThread().onConfigurationChange(conf);
      } catch (IllegalArgumentException iae) {
        Assert.fail("Update smaller configuration failed!");
      }

      // check again after online update
      assertEquals(2, regionServer.getCompactSplitThread().getLargeCompactionThreadNum());
      assertEquals(3, regionServer.getCompactSplitThread().getSmallCompactionThreadNum());
      assertEquals(4, regionServer.getCompactSplitThread().getSplitThreadNum());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testFlushWithTableCompactionDisabled() throws Exception {
    TableDescriptor htd =
      TableDescriptorBuilder.newBuilder(tableName).setCompactionEnabled(false).build();
    TEST_UTIL.createTable(htd, new byte[][] { family }, null);

    // load the table
    for (int i = 0; i < blockingStoreFiles + 1; i++) {
      TEST_UTIL.loadTable(TEST_UTIL.getConnection().getTable(tableName), family);
      TEST_UTIL.flush(tableName);
    }

    // Make sure that store file number is greater than blockingStoreFiles + 1
    Path tableDir = CommonFSUtils.getTableDir(rootDir, tableName);
    Collection<String> hfiles = SnapshotTestingUtils.listHFileNames(fs, tableDir);
    assert (hfiles.size() > blockingStoreFiles + 1);
  }

  @Test
  public void testFlushWithRegionReplicas() throws Exception {
    TableDescriptor htd =
      TableDescriptorBuilder.newBuilder(tableName).setRegionReplication(2).build();
    TEST_UTIL.createTable(htd, new byte[][] { family }, null);

    // load the table
    for (int i = 0; i < blockingStoreFiles + 1; i++) {
      TEST_UTIL.loadTable(TEST_UTIL.getConnection().getTable(tableName), family);
      TEST_UTIL.flush(tableName);
    }

    // One region split should have taken place, because the primary replica gets split, and not the
    // secondary replica.
    assertEquals(1, TEST_UTIL.getRSForFirstRegionInTable(tableName).getCompactSplitThread()
      .getSubmittedSplitsCount());
  }
}
