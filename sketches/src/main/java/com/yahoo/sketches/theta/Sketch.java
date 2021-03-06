/*
 * Copyright 2015-16, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.theta;

import static com.yahoo.sketches.Family.idToFamily;
import static com.yahoo.sketches.HashOperations.count;
import static com.yahoo.sketches.Util.DEFAULT_UPDATE_SEED;
import static com.yahoo.sketches.Util.LS;
import static com.yahoo.sketches.Util.ceilingPowerOf2;
import static com.yahoo.sketches.Util.zeroPad;
import static com.yahoo.sketches.theta.PreambleUtil.COMPACT_FLAG_MASK;
import static com.yahoo.sketches.theta.PreambleUtil.FAMILY_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.FLAGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.MAX_THETA_LONG_AS_DOUBLE;
import static com.yahoo.sketches.theta.PreambleUtil.ORDERED_FLAG_MASK;
import static com.yahoo.sketches.theta.PreambleUtil.PREAMBLE_LONGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.SER_VER_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.extractFamilyID;
import static com.yahoo.sketches.theta.PreambleUtil.extractPreLongs;
import static com.yahoo.sketches.theta.PreambleUtil.extractSerVer;

import com.yahoo.memory.Memory;
import com.yahoo.sketches.BinomialBoundsN;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.Util;

/**
 * The top-level class for all sketches. This class is never constructed directly.
 * Use the UpdateSketch.builder() methods to create UpdateSketches.
 *
 * @author Lee Rhodes
 */
public abstract class Sketch {

  static final int DEFAULT_LG_RESIZE_FACTOR = 3;   //Unique to Heap

  Sketch() {}

  //Sketch, defined here with Javadocs

  /**
   * Gets the unique count estimate.
   * @return the sketch's best estimate of the cardinality of the input stream.
   */
  public double getEstimate() {
    return estimate(getThetaLong(), getRetainedEntries(true), isEmpty());
  }

  /**
   * Gets the approximate lower error bound given the specified number of Standard Deviations.
   * This will return getEstimate() if isEmpty() is true.
   *
   * @param numStdDev
   * <a href="{@docRoot}/resources/dictionary.html#numStdDev">See Number of Standard Deviations</a>
   * @return the lower bound.
   */
  public double getLowerBound(final int numStdDev) {
    return (isEstimationMode())
        ? lowerBound(getRetainedEntries(true), getThetaLong(), numStdDev, isEmpty())
        : getRetainedEntries(true);
  }

  /**
   * Returns the number of valid entries that have been retained by the sketch.
   * @return the number of valid retained entries
   */
  public int getRetainedEntries() {
    return getRetainedEntries(true);
  }

  /**
   * Returns the number of entries that have been retained by the sketch.
   * @param valid if true, returns the number of valid entries, which are less than theta and used
   * for estimation.
   * Otherwise, return the number of all entries, valid or not, that are currently in the internal
   * sketch cache.
   * @return the number of retained entries
   */
  public abstract int getRetainedEntries(boolean valid);

  /**
   * Gets the value of theta as a double with a value between zero and one
   * @return the value of theta as a double
   */
  public double getTheta() {
    return getThetaLong() / MAX_THETA_LONG_AS_DOUBLE;
  }

  /**
   * Gets the number of hash values less than the given theta.
   * @param theta the given theta as a double between zero and one.
   * @return the number of hash values less than the given theta.
   */
  public int getCountLessThanTheta(final double theta) {
    final long thetaLong = (long) (MAX_THETA_LONG_AS_DOUBLE * theta);
    return count(getCache(), thetaLong);
  }

  /**
   * Returns the Family that this sketch belongs to
   * @return the Family that this sketch belongs to
   */
  public abstract Family getFamily();

  /**
   * Gets the approximate upper error bound given the specified number of Standard Deviations.
   * This will return getEstimate() if isEmpty() is true.
   *
   * @param numStdDev
   * <a href="{@docRoot}/resources/dictionary.html#numStdDev">See Number of Standard Deviations</a>
   * @return the upper bound.
   */
  public double getUpperBound(final int numStdDev) {
    return (isEstimationMode())
        ? upperBound(getRetainedEntries(true), getThetaLong(), numStdDev, isEmpty())
        : getRetainedEntries(true);
  }

  /**
   * <a href="{@docRoot}/resources/dictionary.html#empty">See Empty</a>
   * @return true if empty.
   */
  public abstract boolean isEmpty();

  /**
   * Returns true if the sketch is Estimation Mode (as opposed to Exact Mode).
   * This is true if theta &lt; 1.0 AND isEmpty() is false.
   * @return true if the sketch is in estimation mode.
   */
  public boolean isEstimationMode() {
    return estMode(getThetaLong(), isEmpty());
  }

  /**
   * Returns true if the backing resource of this sketch is identical with the backing resource
   * of mem. If the backing resource is a common array or ByteBuffer, the offset and
   * capacity must also be identical.
   * @param mem A given Memory object
   * @return true if the backing resource of this sketch is identical with the backing resource
   * of mem.
   */
  public boolean isSameResource(final Memory mem) {
    return false;
  }

  /**
   * Serialize this sketch to a byte array form.
   * @return byte array of this sketch
   */
  public abstract byte[] toByteArray();

  /**
   * Returns a human readable summary of the sketch.  This method is equivalent to the parameterized
   * call:<br>
   * <i>Sketch.toString(sketch, true, false, 8, true);</i>
   * @return summary
   */
  @Override
  public String toString() {
    return toString(true, false, 8, true);
  }

  /**
   * Gets a human readable listing of contents and summary of the given sketch.
   * This can be a very long string.  If this sketch is in a "dirty" state there
   * may be values in the dataDetail view that are &ge; theta.
   *
   * @param sketchSummary If true the sketch summary will be output at the end.
   * @param dataDetail If true, includes all valid hash values in the sketch.
   * @param width The number of columns of hash values. Default is 8.
   * @param hexMode If true, hashes will be output in hex.
   * @return The result string, which can be very long.
   */
  public String toString(final boolean sketchSummary, final boolean dataDetail, final int width,
      final boolean hexMode) {
    final StringBuilder sb = new StringBuilder();

    final long[] cache = getCache();
    int nomLongs = 0;
    int arrLongs = cache.length;
    long seed = 0;
    float p = 0;
    int rf = 0;
    //int preLongs = getPreambleLongs();
    final boolean updateSketch = (this instanceof UpdateSketch);

    //boolean direct = isDirect();
    final long thetaLong = getThetaLong();
    final int curCount = this.getRetainedEntries(true);

    if (updateSketch) {
      final UpdateSketch uis = (UpdateSketch)this;
      nomLongs = 1 << uis.getLgNomLongs();
      seed = uis.getSeed();
      arrLongs = 1 << uis.getLgArrLongs();
      p = uis.getP();
      rf = uis.getResizeFactor().getValue();
    }

    if (dataDetail) {
      final int w = (width > 0) ? width : 8; // default is 8 wide
      if (curCount > 0) {
        sb.append("### SKETCH DATA DETAIL");
        for (int i = 0, j = 0; i < arrLongs; i++ ) {
          final long h;
          h = cache[i];
          if ((h <= 0) || (h >= thetaLong)) {
            continue;
          }
          if ((j % w) == 0) {
            sb.append(LS).append(String.format("   %6d", (j + 1)));
          }
          if (hexMode) {
            sb.append(" " + zeroPad(Long.toHexString(h), 16) + ",");
          }
          else {
            sb.append(String.format(" %20d,", h));
          }
          j++ ;
        }
        sb.append(LS).append("### END DATA DETAIL").append(LS + LS);
      }
    }

    if (sketchSummary) {
      final double thetaDbl = thetaLong / MAX_THETA_LONG_AS_DOUBLE;
      final String thetaHex = zeroPad(Long.toHexString(thetaLong), 16);
      final String thisSimpleName = this.getClass().getSimpleName();
      final int seedHash = getSeedHash();

      sb.append(LS);
      sb.append("### ").append(thisSimpleName).append(" SUMMARY: ").append(LS);
      if (updateSketch) {
        sb.append("   Nominal Entries (k)     : ").append(nomLongs).append(LS);
      }
      sb.append("   Estimate                : ").append(getEstimate()).append(LS);
      sb.append("   Upper Bound, 95% conf   : ").append(getUpperBound(2)).append(LS);
      sb.append("   Lower Bound, 95% conf   : ").append(getLowerBound(2)).append(LS);
      if (updateSketch) {
        sb.append("   p                       : ").append(p).append(LS);
      }
      sb.append("   Theta (double)          : ").append(thetaDbl).append(LS);
      sb.append("   Theta (long)            : ").append(thetaLong).append(LS);
      sb.append("   Theta (long) hex        : ").append(thetaHex).append(LS);
      sb.append("   EstMode?                : ").append(isEstimationMode()).append(LS);
      sb.append("   Empty?                  : ").append(isEmpty()).append(LS);
      if (updateSketch) {
        sb.append("   Resize Factor           : ").append(rf).append(LS);
      }
      sb.append("   Array Size Entries      : ").append(arrLongs).append(LS);
      sb.append("   Retained Entries        : ").append(curCount).append(LS);
      if (updateSketch) {
        sb.append("   Update Seed             : ")
          .append(Long.toHexString(seed)).append(" | ")
          .append(Long.toString(seed)).append(LS);
      }
      sb.append("   Seed Hash               : ").append(seedHash).append(LS);
      sb.append("### END SKETCH SUMMARY").append(LS);

    }
    return sb.toString();
  }

  //public static methods

  /**
   * Heapify takes the sketch image in Memory and instantiates an on-heap
   * Sketch using the
   * <a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">Default Update Seed</a>.
   * The resulting sketch will not retain any link to the source Memory.
   * @param srcMem an image of a Sketch where the image seed hash matches the default seed hash.
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @return a Heap-based Sketch from the given Memory
   */
  public static Sketch heapify(final Memory srcMem) {
    return heapify(srcMem, DEFAULT_UPDATE_SEED);
  }

  /**
   * Heapify takes the sketch image in Memory and instantiates an on-heap
   * Sketch using the given seed.
   * The resulting sketch will not retain any link to the source Memory.
   * @param srcMem an image of a Sketch where the image seed hash matches the given seed hash.
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   * Compact sketches store a 16-bit hash of the seed, but not the seed itself.
   * @return a Heap-based Sketch from the given Memory
   */
  public static Sketch heapify(final Memory srcMem, final long seed) {
    final int serVer = srcMem.getByte(SER_VER_BYTE);
    if (serVer == 3) {
      final byte famID = srcMem.getByte(FAMILY_BYTE);
      final boolean ordered = srcMem.isAnyBitsSet(FLAGS_BYTE, (byte) ORDERED_FLAG_MASK);
      return constructHeapSketch(famID, ordered, srcMem, seed);
    }
    if (serVer == 1) {
      return ForwardCompatibility.heapify1to3(srcMem, seed);
    }
    if (serVer == 2) {
      return ForwardCompatibility.heapify2to3(srcMem, seed);
    }
    throw new SketchesArgumentException("Unknown Serialization Version: " + serVer);
  }

  /**
   * Wrap takes the sketch image in Memory and refers to it directly. There is no data copying onto
   * the java heap.  Only "Direct" Serialization Version 3 (i.e, OpenSource) sketches that have
   * been explicitly stored as direct objects can be wrapped. This method assumes the
   * {@link Util#DEFAULT_UPDATE_SEED}.
   * <a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">Default Update Seed</a>.
   * @param srcMem an image of a Sketch where the image seed hash matches the default seed hash.
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @return a Sketch backed by the given Memory
   */
  public static Sketch wrap(final Memory srcMem) {
    return wrap(srcMem, DEFAULT_UPDATE_SEED);
  }

  /**
   * Wrap takes the sketch image in Memory and refers to it directly. There is no data copying onto
   * the java heap.  Only "Direct" Serialization Version 3 (i.e, OpenSource) sketches that have
   * been explicity stored as direct objects can be wrapped.
   * An attempt to "wrap" earlier version sketches will result in a "heapified", normal
   * Java Heap version of the sketch where all data will be copied to the heap.
   * @param srcMem an image of a Sketch where the image seed hash matches the given seed hash.
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   * Compact sketches store a 16-bit hash of the seed, but not the seed itself.
   * @return a UpdateSketch backed by the given Memory
   */
  public static Sketch wrap(final Memory srcMem, final long seed) {
    final boolean readOnly = srcMem.isReadOnly();
    final boolean direct = srcMem.isDirect();

    final long pre0 = srcMem.getLong(0);
    final int preLongs;
    final int serVer;
    final int familyID;
    if (readOnly && !direct) {
      preLongs = srcMem.getByte(PREAMBLE_LONGS_BYTE) & 0X3F;
      serVer = srcMem.getByte(SER_VER_BYTE) & 0XFF;
      familyID = srcMem.getByte(FAMILY_BYTE) & 0XFF;
    } else {
      final Object memObj = srcMem.array(); //may be null
      final long memAdd = srcMem.getCumulativeOffset(0L);
      preLongs = extractPreLongs(memObj, memAdd);
      serVer = extractSerVer(memObj, memAdd);
      familyID = extractFamilyID(memObj, memAdd);
    }
    final Family family = Family.idToFamily(familyID);
    switch (family) {
      case QUICKSELECT: { //Hash Table structure
        if ((serVer == 3) && (preLongs == 3)) {
          return DirectQuickSelectSketch.wrapInstance(srcMem, seed);
        } else {
          throw new SketchesArgumentException(
              "Corrupted: " + family + " family image: must have SerVer = 3 and preLongs = 3");
        }
      }
      case COMPACT: { //serVer 1, 2, or 3, preLongs = 1, 2, or 3
        if (serVer == 1) {
          return ForwardCompatibility.heapify1to3(srcMem, seed);
        }
        else if (serVer == 2) {
          return ForwardCompatibility.heapify2to3(srcMem, seed);
        }
        final int flags = srcMem.getByte(FLAGS_BYTE);
        final boolean compact = (flags & COMPACT_FLAG_MASK) > 0; //used for corruption check
        final boolean ordered = (flags & ORDERED_FLAG_MASK) > 0;
        if (compact) {
            return ordered ? DirectCompactOrderedSketch.wrapInstance(srcMem, pre0, seed)
                           : DirectCompactSketch.wrapInstance(srcMem, pre0, seed);
        }
        throw new SketchesArgumentException(
            "Corrupted: " + family + " family image must have compact flag set");
      }
      default: throw new SketchesArgumentException(
          "Sketch cannot wrap family: " + family + " as a Sketch");
    }
  }

  //Sizing methods

  /**
   * Returns the number of storage bytes required for this Sketch in its current state.
   * @param compact if true, returns the bytes required for compact form.
   * If this sketch is already in compact form this parameter is ignored.
   * @return the number of storage bytes required for this sketch
   */
  public int getCurrentBytes(final boolean compact) {
    final int preBytes = getCurrentPreambleLongs(compact) << 3;
    final int dataBytes = getCurrentDataLongs(compact) << 3;
    return preBytes + dataBytes;
  }

  /**
   * Returns the maximum number of storage bytes required for a CompactSketch with the given
   * number of actual entries.
   * @param numberOfEntries the actual number of entries stored with the CompactSketch.
   * @return the maximum number of storage bytes required for a CompactSketch with the given number
   * of entries.
   */
  public static int getMaxCompactSketchBytes(final int numberOfEntries) {
    return (numberOfEntries << 3) + (Family.COMPACT.getMaxPreLongs() << 3);
  }

  /**
   * Returns the maximum number of storage bytes required for an UpdateSketch with the given
   * number of nominal entries (power of 2).
   * @param nomEntries <a href="{@docRoot}/resources/dictionary.html#nomEntries">Nominal Entres</a>
   * This will become the ceiling power of 2 if it is not.
   * @return the maximum number of storage bytes required for a UpdateSketch with the given
   * nomEntries
   */
  public static int getMaxUpdateSketchBytes(final int nomEntries) {
    final int nomEnt = ceilingPowerOf2(nomEntries);
    return (nomEnt << 4) + (Family.QUICKSELECT.getMaxPreLongs() << 3);
  }

  /**
   * Returns the serialization version from the given Memory
   * @param mem the sketch Memory
   * @return the serialization version from the Memory
   */
  public static int getSerializationVersion(final Memory mem) {
    return mem.getByte(SER_VER_BYTE);
  }

  /**
   * Returns true if this sketch is in compact form.
   * @return true if this sketch is in compact form.
   */
  public abstract boolean isCompact();

  /**
   * Returns true if internal cache is ordered
   * @return true if internal cache is ordered
   */
  public abstract boolean isOrdered();

  /**
   * Returns true if this sketch accesses its internal data using the Memory package
   * @return true if this sketch accesses its internal data using the Memory package
   */
  public abstract boolean isDirect();

  //Restricted methods

  final int getCurrentDataLongs(final boolean compact) {
    final int longs;
    if ((this instanceof CompactSketch) || compact) {
      longs = getRetainedEntries(true);
    }
    else { //must be update sketch
      longs = (1 << ((UpdateSketch)this).getLgArrLongs());
    }
    return longs;
  }

  final int getCurrentPreambleLongs(final boolean compact) {
    return compact ? compactPreambleLongs(getThetaLong(), isEmpty()) : getPreambleLongs();
  }

  final static int compactPreambleLongs(final long thetaLong, final boolean empty) {
    return (thetaLong < Long.MAX_VALUE) ? 3 : empty ? 1 : 2;
  }

  /**
   * Returns preamble longs if stored in current state
   * @return preamble longs if stored in current state
   */
  abstract int getPreambleLongs();

  /**
   * Gets the 16-bit seed hash
   * @return the seed hash
   */
  abstract short getSeedHash();

  /**
   * Gets the value of theta as a long
   * @return the value of theta as a long
   */
  abstract long getThetaLong();

  /**
   * Gets the internal cache array.
   * @return the internal cache array.
   */
  abstract long[] getCache();

  /**
   * Gets the <a href="{@docRoot}/resources/dictionary.html#mem">Memory</a>
   * if available, otherwise returns null.
   * @return the backing Memory or null.
   */
  abstract Memory getMemory();

  /**
   * Returns true if given Family id is one of the theta sketches
   * @param id the given Family id
   * @return true if given Family id is one of the theta sketches
   */
  static boolean isValidSketchID(final int id) {
    return (id == Family.ALPHA.getID())
        || (id == Family.QUICKSELECT.getID())
        || (id == Family.COMPACT.getID());
  }

  static final double estimate(final long thetaLong, final int curCount, final boolean empty) {
    if (estMode(thetaLong, empty)) {
      final double theta = thetaLong / MAX_THETA_LONG_AS_DOUBLE;
      return curCount / theta;
    }
    return curCount;
  }

  static final double lowerBound(final int curCount, final long thetaLong, final int numStdDev,
      final boolean empty) {
    final double theta = thetaLong / MAX_THETA_LONG_AS_DOUBLE;
    return BinomialBoundsN.getLowerBound(curCount, theta, numStdDev, empty);
  }

  static final double upperBound(final int curCount, final long thetaLong, final int numStdDev,
      final boolean empty) {
    final double theta = thetaLong / MAX_THETA_LONG_AS_DOUBLE;
    return BinomialBoundsN.getUpperBound(curCount, theta, numStdDev, empty);
  }

  private static final boolean estMode(final long thetaLong, final boolean empty) {
    return (thetaLong < Long.MAX_VALUE) && !empty;
  }

  /**
   * Instantiates a Heap Sketch from Memory.
   * @param famID the Family ID
   * @param ordered true if the sketch is of the Compact family and ordered
   * @param srcMem <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   * The seed required to instantiate a non-compact sketch.
   * @return a Sketch
   */
  private static final Sketch constructHeapSketch(final byte famID, final boolean ordered,
      final Memory srcMem, final long seed) {
    final boolean compact = srcMem.isAnyBitsSet(FLAGS_BYTE, (byte) COMPACT_FLAG_MASK);
    final Family family = idToFamily(famID);
    switch (family) {
      case ALPHA: {
        if (compact) {
          throw new SketchesArgumentException("Possibly Corrupted " + family
              + " image: cannot be compact");
        }
        return HeapAlphaSketch.heapifyInstance(srcMem, seed);
      }
      case QUICKSELECT: {
        return HeapQuickSelectSketch.heapifyInstance(srcMem, seed);
      }
      case COMPACT: {
        if (!compact) {
          throw new SketchesArgumentException("Possibly Corrupted " + family
              + " image: must be compact");
        }
        return ordered ? HeapCompactOrderedSketch.heapifyInstance(srcMem, seed)
                       : HeapCompactSketch.heapifyInstance(srcMem, seed);
      }
      default: {
        throw new SketchesArgumentException("Sketch cannot heapify family: " + family
            + " as a Sketch");
      }
    }
  }

}
