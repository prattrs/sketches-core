/*
 * Copyright 2016, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.quantiles;

import static com.yahoo.sketches.quantiles.PreambleUtil.COMPACT_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.EMPTY_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.ORDERED_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.READ_ONLY_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertFamilyID;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertFlags;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertK;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertMaxDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertMinDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertN;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertPreLongs;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertSerVer;

import java.util.Arrays;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.Family;

/**
 * The doubles to byte array algorithms.
 *
 * @author Lee Rhodes
 * @author Jon Malkin
 */
final class DoublesByteArrayImpl {

  private DoublesByteArrayImpl() {}

  static byte[] toByteArray(final DoublesSketch sketch, final boolean ordered,
                            final boolean compact) {
    final boolean empty = sketch.isEmpty();

    //create the flags byte
    final int flags = (empty ? EMPTY_FLAG_MASK : 0)
        | (ordered ? ORDERED_FLAG_MASK : 0)
        | (compact ? (COMPACT_FLAG_MASK | READ_ONLY_FLAG_MASK) : 0);

    if (empty && !sketch.isDirect()) {
      final byte[] outByteArr = new byte[Long.BYTES];
      final Memory memOut = new NativeMemory(outByteArr);
      final Object memObj = memOut.array();
      final long memAdd = memOut.getCumulativeOffset(0L);
      final int preLongs = 1;
      insertPre0(memObj, memAdd, preLongs, flags, sketch.getK());
      return outByteArr;
    }
    //not empty || not compact; flags passed for convenience
    return convertToByteArray(sketch, flags, ordered, compact);
  }

  /**
   * Returns a byte array, including preamble, min, max and data extracted from the sketch.
   * @param sketch the given DoublesSketch
   * @param ordered true if the desired form of the resulting array has the base buffer sorted.
   * @param compact true if the desired form of the resulting array is in compact form.
   * @return a byte array, including preamble, min, max and data extracted from the Combined Buffer.
   */
  private static byte[] convertToByteArray(final DoublesSketch sketch, final int flags,
                                           final boolean ordered, final boolean compact) {
    final int preLongs = 2;
    final int extra = 2; // extra space for min and max values
    final int prePlusExtraBytes = (preLongs + extra) << 3;
    final int k = sketch.getK();
    final long n = sketch.getN();

    // If not-compact, have accessor always report full levels. Then use level size to determine
    // whether to copy data out.
    final DoublesSketchAccessor dsa = DoublesSketchAccessor.wrap(sketch, !compact);

    final int outBytes = (compact ? sketch.getCompactStorageBytes() : sketch.getUpdatableStorageBytes());

    final byte[] outByteArr = new byte[outBytes];
    final Memory memOut = new NativeMemory(outByteArr);
    final Object memObj = memOut.array();
    final long memAdd = memOut.getCumulativeOffset(0L);

    //insert preamble-0, N, min, max
    insertPre0(memObj, memAdd, preLongs, flags, k);
    if (sketch.isEmpty()) { return outByteArr; }

    insertN(memObj, memAdd, n);
    insertMinDouble(memObj, memAdd, sketch.getMinValue());
    insertMaxDouble(memObj, memAdd, sketch.getMaxValue());

    long memOffsetBytes = prePlusExtraBytes;

    // might need to sort base buffer but don't want to change input sketch
    final int bbCnt = Util.computeBaseBufferItems(k, n);
    if (bbCnt > 0) { //Base buffer items only
      final double[] bbItemsArr = dsa.getArray(0, bbCnt);
      if (ordered) { Arrays.sort(bbItemsArr); }
      memOut.putDoubleArray(memOffsetBytes, bbItemsArr, 0, bbCnt);
    }
    // If n < 2k, totalLevels == 0 so ok to overshoot the offset update
    memOffsetBytes += (compact ? bbCnt : 2 * k) << 3;

    // If serializing from a compact sketch to a non-compact form, we may end up copying data for a
    // higher level one or more times into an unused level. A bit wasteful, but not incorrect.
    final int totalLevels = Util.computeTotalLevels(sketch.getBitPattern());
    for (int lvl = 0; lvl < totalLevels; ++lvl) {
      dsa.setLevel(lvl);
      if (dsa.numItems() > 0) {
        assert dsa.numItems() == k;
        memOut.putDoubleArray(memOffsetBytes, dsa.getArray(0, k), 0, k);
        memOffsetBytes += (k << 3);
      }
    }

    return outByteArr;
  }

  private static void insertPre0(final Object memObj, final long memAdd,
      final int preLongs, final int flags, final int k) {
    insertPreLongs(memObj, memAdd, preLongs);
    insertSerVer(memObj, memAdd, DoublesSketch.DOUBLES_SER_VER);
    insertFamilyID(memObj, memAdd, Family.QUANTILES.getID());
    insertFlags(memObj, memAdd, flags);
    insertK(memObj, memAdd, k);
  }

}
