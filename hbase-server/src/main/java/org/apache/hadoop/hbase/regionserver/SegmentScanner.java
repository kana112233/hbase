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

import java.io.IOException;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.function.IntConsumer;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.PrivateCellUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.monitoring.ThreadLocalServerSideScanMetrics;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A scanner of a single memstore segment.
 */
@InterfaceAudience.Private
public class SegmentScanner implements KeyValueScanner {

  // the observed structure
  protected final Segment segment;
  // the highest relevant MVCC
  private long readPoint;
  // the current iterator that can be reinitialized by
  // seek(), backwardSeek(), or reseek()
  protected Iterator<ExtendedCell> iter;
  // the pre-calculated cell to be returned by peek()
  protected ExtendedCell current = null;
  // or next()
  // A flag represents whether could stop skipping KeyValues for MVCC
  // if have encountered the next row. Only used for reversed scan
  private boolean stopSkippingKVsIfNextRow = false;
  // Stop skipping KeyValues for MVCC if finish this row. Only used for reversed scan
  private ExtendedCell stopSkippingKVsRow;
  // last iterated KVs by seek (to restore the iterator state after reseek)
  private ExtendedCell last = null;

  // flag to indicate if this scanner is closed
  protected boolean closed = false;
  private boolean isScanMetricsEnabled = false;

  /**
   * Scanners are ordered from 0 (oldest) to newest in increasing order.
   */
  protected SegmentScanner(Segment segment, long readPoint) {
    this.segment = segment;
    this.readPoint = readPoint;
    // increase the reference count so the underlying structure will not be de-allocated
    this.segment.incScannerCount();
    iter = segment.iterator();
    // the initialization of the current is required for working with heap of SegmentScanners
    updateCurrent();
    // Enable scan metrics for tracking bytes read after initialization of current
    this.isScanMetricsEnabled = ThreadLocalServerSideScanMetrics.isScanMetricsEnabled();
    if (current == null) {
      // nothing to fetch from this scanner
      close();
    }
  }

  /**
   * Look at the next Cell in this scanner, but do not iterate the scanner
   * @return the currently observed Cell
   */
  @Override
  public ExtendedCell peek() { // sanity check, the current should be always valid
    if (closed) {
      return null;
    }
    if (current != null && current.getSequenceId() > readPoint) {
      throw new RuntimeException("current is invalid: read point is " + readPoint + ", "
        + "while current sequence id is " + current.getSequenceId());
    }
    return current;
  }

  /**
   * Return the next Cell in this scanner, iterating the scanner
   * @return the next Cell or null if end of scanner
   */
  @Override
  public ExtendedCell next() throws IOException {
    if (closed) {
      return null;
    }
    ExtendedCell oldCurrent = current;
    updateCurrent(); // update the currently observed Cell
    return oldCurrent;
  }

  /**
   * Seek the scanner at or after the specified Cell.
   * @param cell seek value
   * @return true if scanner has values left, false if end of scanner
   */
  @Override
  public boolean seek(ExtendedCell cell) throws IOException {
    if (closed) {
      return false;
    }
    if (cell == null) {
      close();
      return false;
    }
    // restart the iterator from new key
    iter = getIterator(cell);
    // last is going to be reinitialized in the next getNext() call
    last = null;
    updateCurrent();
    return (current != null);
  }

  protected Iterator<ExtendedCell> getIterator(ExtendedCell cell) {
    return segment.tailSet(cell).iterator();
  }

  /**
   * Reseek the scanner at or after the specified KeyValue. This method is guaranteed to seek at or
   * after the required key only if the key comes after the current position of the scanner. Should
   * not be used to seek to a key which may come before the current position.
   * @param cell seek value (should be non-null)
   * @return true if scanner has values left, false if end of scanner
   */
  @Override
  public boolean reseek(ExtendedCell cell) throws IOException {
    if (closed) {
      return false;
    }
    /*
     * See HBASE-4195 & HBASE-3855 & HBASE-6591 for the background on this implementation. This code
     * is executed concurrently with flush and puts, without locks. The ideal implementation for
     * performance would use the sub skip list implicitly pointed by the iterator. Unfortunately the
     * Java API does not offer a method to get it. So we remember the last keys we iterated to and
     * restore the reseeked set to at least that point.
     */
    iter = getIterator(getHighest(cell, last));
    updateCurrent();
    return (current != null);
  }

  /**
   * Seek the scanner at or before the row of specified Cell, it firstly tries to seek the scanner
   * at or after the specified Cell, return if peek KeyValue of scanner has the same row with
   * specified Cell, otherwise seek the scanner at the first Cell of the row which is the previous
   * row of specified KeyValue
   * @param key seek Cell
   * @return true if the scanner is at the valid KeyValue, false if such Cell does not exist
   */
  @Override
  public boolean backwardSeek(ExtendedCell key) throws IOException {
    if (closed) {
      return false;
    }
    seek(key); // seek forward then go backward
    if (peek() == null || segment.compareRows(peek(), key) > 0) {
      return seekToPreviousRow(key);
    }
    return true;
  }

  /**
   * Seek the scanner at the first Cell of the row which is the previous row of specified key
   * @param cell seek value
   * @return true if the scanner at the first valid Cell of previous row, false if not existing such
   *         Cell
   */
  @Override
  public boolean seekToPreviousRow(ExtendedCell cell) throws IOException {
    if (closed) {
      return false;
    }
    boolean keepSeeking;
    Cell key = cell;
    do {
      ExtendedCell firstKeyOnRow = PrivateCellUtil.createFirstOnRow(key);
      SortedSet<ExtendedCell> cellHead = segment.headSet(firstKeyOnRow);
      Cell lastCellBeforeRow = cellHead.isEmpty() ? null : cellHead.last();
      if (lastCellBeforeRow == null) {
        current = null;
        return false;
      }
      ExtendedCell firstKeyOnPreviousRow = PrivateCellUtil.createFirstOnRow(lastCellBeforeRow);
      this.stopSkippingKVsIfNextRow = true;
      this.stopSkippingKVsRow = firstKeyOnPreviousRow;
      seek(firstKeyOnPreviousRow);
      this.stopSkippingKVsIfNextRow = false;
      if (
        peek() == null || segment.getComparator().compareRows(peek(), firstKeyOnPreviousRow) > 0
      ) {
        keepSeeking = true;
        key = firstKeyOnPreviousRow;
        continue;
      } else {
        keepSeeking = false;
      }
    } while (keepSeeking);
    return true;
  }

  /**
   * Seek the scanner at the first KeyValue of last row
   * @return true if scanner has values left, false if the underlying data is empty
   */
  @Override
  public boolean seekToLastRow() throws IOException {
    if (closed) {
      return false;
    }
    ExtendedCell higherCell = segment.isEmpty() ? null : segment.last();
    if (higherCell == null) {
      return false;
    }

    ExtendedCell firstCellOnLastRow = PrivateCellUtil.createFirstOnRow(higherCell);

    if (seek(firstCellOnLastRow)) {
      return true;
    } else {
      return seekToPreviousRow(higherCell);
    }
  }

  /**
   * Close the KeyValue scanner.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    getSegment().decScannerCount();
    closed = true;
  }

  /**
   * This functionality should be resolved in the higher level which is MemStoreScanner, currently
   * returns true as default. Doesn't throw IllegalStateException in order not to change the
   * signature of the overridden method
   */
  @Override
  public boolean shouldUseScanner(Scan scan, HStore store, long oldestUnexpiredTS) {
    return getSegment().shouldSeek(scan.getColumnFamilyTimeRange().getOrDefault(
      store.getColumnFamilyDescriptor().getName(), scan.getTimeRange()), oldestUnexpiredTS);
  }

  @Override
  public boolean requestSeek(ExtendedCell c, boolean forward, boolean useBloom) throws IOException {
    return NonLazyKeyValueScanner.doRealSeek(this, c, forward);
  }

  /**
   * This scanner is working solely on the in-memory MemStore and doesn't work on store files,
   * MutableCellSetSegmentScanner always does the seek, therefore always returning true.
   */
  @Override
  public boolean realSeekDone() {
    return true;
  }

  /**
   * This function should be never called on scanners that always do real seek operations (i.e. most
   * of the scanners and also this one). The easiest way to achieve this is to call
   * {@link #realSeekDone()} first.
   */
  @Override
  public void enforceSeek() throws IOException {
    throw new NotImplementedException("enforceSeek cannot be called on a SegmentScanner");
  }

  /** Returns true if this is a file scanner. Otherwise a memory scanner is assumed. */
  @Override
  public boolean isFileScanner() {
    return false;
  }

  @Override
  public void recordBlockSize(IntConsumer blockSizeConsumer) {
    // do nothing
  }

  @Override
  public Path getFilePath() {
    return null;
  }

  /**
   * @return the next key in the index (the key to seek to the next block) if known, or null
   *         otherwise Not relevant for in-memory scanner
   */
  @Override
  public ExtendedCell getNextIndexedKey() {
    return null;
  }

  /**
   * Called after a batch of rows scanned (RPC) and set to be returned to client. Any in between
   * cleanup can be done here. Nothing to be done for MutableCellSetSegmentScanner.
   */
  @Override
  public void shipped() throws IOException {
    // do nothing
  }

  // debug method
  @Override
  public String toString() {
    String res = "Store segment scanner of type " + this.getClass().getName() + "; ";
    res += "Scanner order " + getScannerOrder() + "; ";
    res += getSegment().toString();
    return res;
  }

  /********************* Private Methods **********************/

  private Segment getSegment() {
    return segment;
  }

  /**
   * Private internal method for iterating over the segment, skipping the cells with irrelevant MVCC
   */
  protected void updateCurrent() {
    ExtendedCell next = null;
    long totalBytesRead = 0;

    try {
      while (iter.hasNext()) {
        next = iter.next();
        if (isScanMetricsEnabled) {
          // Batch collect bytes to reduce method call overhead
          totalBytesRead += Segment.getCellLength(next);
        }
        if (next.getSequenceId() <= this.readPoint) {
          current = next;
          return;// skip irrelevant versions
        }
        // for backwardSeek() stay in the boundaries of a single row
        if (stopSkippingKVsIfNextRow && segment.compareRows(next, stopSkippingKVsRow) > 0) {
          current = null;
          return;
        }
      } // end of while

      current = null; // nothing found
    } finally {
      // Add accumulated bytes before returning
      if (totalBytesRead > 0) {
        ThreadLocalServerSideScanMetrics.addBytesReadFromMemstore(totalBytesRead);
      }
      if (next != null) {
        // in all cases, remember the last KV we iterated to, needed for reseek()
        last = next;
      }
    }
  }

  /**
   * Private internal method that returns the higher of the two key values, or null if they are both
   * null
   */
  private ExtendedCell getHighest(ExtendedCell first, ExtendedCell second) {
    if (first == null && second == null) {
      return null;
    }
    if (first != null && second != null) {
      int compare = segment.compare(first, second);
      return (compare > 0 ? first : second);
    }
    return (first != null ? first : second);
  }
}
