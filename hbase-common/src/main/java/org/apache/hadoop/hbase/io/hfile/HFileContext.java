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
package org.apache.hadoop.hbase.io.hfile;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.InnerStoreCellComparator;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.crypto.Encryption;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.encoding.IndexBlockEncoding;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ChecksumType;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Read-only HFile Context Information. Meta data that is used by HFileWriter/Readers and by
 * HFileBlocks. Create one using the {@link HFileContextBuilder} (See HFileInfo and the HFile
 * Trailer class).
 * @see HFileContextBuilder
 */
@InterfaceAudience.Private
public class HFileContext implements HeapSize, Cloneable {
  public static final long FIXED_OVERHEAD = ClassSize.estimateBase(HFileContext.class, false);

  private static final int DEFAULT_BYTES_PER_CHECKSUM = 16 * 1024;

  /** Whether checksum is enabled or not **/
  private boolean usesHBaseChecksum = true;
  /** Whether mvcc is to be included in the Read/Write **/
  private boolean includesMvcc = true;
  /** Whether tags are to be included in the Read/Write **/
  private boolean includesTags;
  /** Compression algorithm used **/
  private Compression.Algorithm compressAlgo = Compression.Algorithm.NONE;
  /**
   * Details used by compression algorithm that are more efficiently loaded once and then reused
   **/
  @Nullable
  private Compression.HFileDecompressionContext decompressionContext = null;
  /** Whether tags to be compressed or not **/
  private boolean compressTags;
  /** the checksum type **/
  private ChecksumType checksumType = ChecksumType.getDefaultChecksumType();
  /** the number of bytes per checksum value **/
  private int bytesPerChecksum = DEFAULT_BYTES_PER_CHECKSUM;
  /** Number of uncompressed bytes we allow per block. */
  private int blockSize = HConstants.DEFAULT_BLOCKSIZE;
  private DataBlockEncoding encoding = DataBlockEncoding.NONE;
  private IndexBlockEncoding indexBlockEncoding = IndexBlockEncoding.NONE;
  /** Encryption algorithm and key used */
  private Encryption.Context cryptoContext = Encryption.Context.NONE;
  private long fileCreateTime;
  private String hfileName;
  private byte[] columnFamily;
  private byte[] tableName;
  private CellComparator cellComparator;

  // Empty constructor. Go with setters
  public HFileContext() {
  }

  /**
   * Copy constructor
   */
  public HFileContext(HFileContext context) {
    this.usesHBaseChecksum = context.usesHBaseChecksum;
    this.includesMvcc = context.includesMvcc;
    this.includesTags = context.includesTags;
    this.compressAlgo = context.compressAlgo;
    this.decompressionContext = context.decompressionContext;
    this.compressTags = context.compressTags;
    this.checksumType = context.checksumType;
    this.bytesPerChecksum = context.bytesPerChecksum;
    this.blockSize = context.blockSize;
    this.encoding = context.encoding;
    this.cryptoContext = context.cryptoContext;
    this.fileCreateTime = context.fileCreateTime;
    this.hfileName = context.hfileName;
    this.columnFamily = context.columnFamily;
    this.tableName = context.tableName;
    this.cellComparator = context.cellComparator;
    this.indexBlockEncoding = context.indexBlockEncoding;
  }

  HFileContext(boolean useHBaseChecksum, boolean includesMvcc, boolean includesTags,
    Compression.Algorithm compressAlgo, Compression.HFileDecompressionContext decompressionContext,
    boolean compressTags, ChecksumType checksumType, int bytesPerChecksum, int blockSize,
    DataBlockEncoding encoding, Encryption.Context cryptoContext, long fileCreateTime,
    String hfileName, byte[] columnFamily, byte[] tableName, CellComparator cellComparator,
    IndexBlockEncoding indexBlockEncoding) {
    this.usesHBaseChecksum = useHBaseChecksum;
    this.includesMvcc = includesMvcc;
    this.includesTags = includesTags;
    this.compressAlgo = compressAlgo;
    this.decompressionContext = decompressionContext;
    this.compressTags = compressTags;
    this.checksumType = checksumType;
    this.bytesPerChecksum = bytesPerChecksum;
    this.blockSize = blockSize;
    if (encoding != null) {
      this.encoding = encoding;
    }
    if (indexBlockEncoding != null) {
      this.indexBlockEncoding = indexBlockEncoding;
    }
    this.cryptoContext = cryptoContext;
    this.fileCreateTime = fileCreateTime;
    this.hfileName = hfileName;
    this.columnFamily = columnFamily;
    this.tableName = tableName;
    // If no cellComparator specified, make a guess based off tablename. If hbase:meta, then should
    // be the meta table comparator. Comparators are per table.
    this.cellComparator = cellComparator != null ? cellComparator
      : this.tableName != null
        ? InnerStoreCellComparator.getInnerStoreCellComparator(this.tableName)
      : InnerStoreCellComparator.INNER_STORE_COMPARATOR;
  }

  /** Returns true when on-disk blocks are compressed, and/or encrypted; false otherwise. */
  public boolean isCompressedOrEncrypted() {
    Compression.Algorithm compressAlgo = getCompression();
    boolean compressed = compressAlgo != null && compressAlgo != Compression.Algorithm.NONE;

    Encryption.Context cryptoContext = getEncryptionContext();
    boolean encrypted = cryptoContext != null && cryptoContext != Encryption.Context.NONE;

    return compressed || encrypted;
  }

  public Compression.Algorithm getCompression() {
    return compressAlgo;
  }

  /**
   * Get an object that, if non-null, may be cast into a codec-specific type that exposes some
   * information from the store-file-specific Configuration that is relevant to decompression. For
   * example, ZSTD tables can have "hbase.io.compress.zstd.dictionary" on their table descriptor,
   * and decompressions of blocks in that table must use that dictionary. It's cheaper for HBase to
   * load these settings into an object of their own once and check this upon each block
   * decompression, than it is to call into {@link Configuration#get(String)} on each block
   * decompression.
   */
  @Nullable
  public Compression.HFileDecompressionContext getDecompressionContext() {
    return decompressionContext;
  }

  public boolean isUseHBaseChecksum() {
    return usesHBaseChecksum;
  }

  public boolean isIncludesMvcc() {
    return includesMvcc;
  }

  public void setIncludesMvcc(boolean includesMvcc) {
    this.includesMvcc = includesMvcc;
  }

  public boolean isIncludesTags() {
    return includesTags;
  }

  public void setIncludesTags(boolean includesTags) {
    this.includesTags = includesTags;
  }

  public void setFileCreateTime(long fileCreateTime) {
    this.fileCreateTime = fileCreateTime;
  }

  public boolean isCompressTags() {
    return compressTags;
  }

  public void setCompressTags(boolean compressTags) {
    this.compressTags = compressTags;
  }

  public ChecksumType getChecksumType() {
    return checksumType;
  }

  public int getBytesPerChecksum() {
    return bytesPerChecksum;
  }

  public int getBlocksize() {
    return blockSize;
  }

  public long getFileCreateTime() {
    return fileCreateTime;
  }

  public DataBlockEncoding getDataBlockEncoding() {
    return encoding;
  }

  public IndexBlockEncoding getIndexBlockEncoding() {
    return indexBlockEncoding;
  }

  public Encryption.Context getEncryptionContext() {
    return cryptoContext;
  }

  public void setEncryptionContext(Encryption.Context cryptoContext) {
    this.cryptoContext = cryptoContext;
  }

  public String getHFileName() {
    return this.hfileName;
  }

  public byte[] getColumnFamily() {
    return this.columnFamily;
  }

  public byte[] getTableName() {
    return this.tableName;
  }

  public CellComparator getCellComparator() {
    return this.cellComparator;
  }

  /**
   * HeapSize implementation. NOTE : The heap size should be altered when new state variable are
   * added.
   * @return heap size of the HFileContext
   */
  @Override
  public long heapSize() {
    long size = FIXED_OVERHEAD;
    if (this.hfileName != null) {
      size += ClassSize.STRING + this.hfileName.length();
    }
    if (this.columnFamily != null) {
      size += ClassSize.sizeOfByteArray(this.columnFamily.length);
    }
    if (this.tableName != null) {
      size += ClassSize.sizeOfByteArray(this.tableName.length);
    }
    if (this.decompressionContext != null) {
      size += this.decompressionContext.heapSize();
    }
    return size;
  }

  @Override
  public HFileContext clone() {
    try {
      return (HFileContext) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(); // Won't happen
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    sb.append("usesHBaseChecksum=");
    sb.append(usesHBaseChecksum);
    sb.append(", checksumType=");
    sb.append(checksumType);
    sb.append(", bytesPerChecksum=");
    sb.append(bytesPerChecksum);
    sb.append(", blocksize=");
    sb.append(blockSize);
    sb.append(", encoding=");
    sb.append(encoding);
    sb.append(", indexBlockEncoding=");
    sb.append(indexBlockEncoding);
    sb.append(", includesMvcc=");
    sb.append(includesMvcc);
    sb.append(", includesTags=");
    sb.append(includesTags);
    sb.append(", compressAlgo=");
    sb.append(compressAlgo);
    sb.append(", compressTags=");
    sb.append(compressTags);
    sb.append(", decompressionContext=");
    sb.append(decompressionContext);
    sb.append(", cryptoContext=[");
    sb.append(cryptoContext);
    sb.append("]");
    if (hfileName != null) {
      sb.append(", name=");
      sb.append(hfileName);
    }
    if (tableName != null) {
      sb.append(", tableName=");
      sb.append(Bytes.toStringBinary(tableName));
    }
    if (columnFamily != null) {
      sb.append(", columnFamily=");
      sb.append(Bytes.toStringBinary(columnFamily));
    }
    sb.append(", cellComparator=");
    sb.append(this.cellComparator);
    sb.append("]");
    return sb.toString();
  }
}
