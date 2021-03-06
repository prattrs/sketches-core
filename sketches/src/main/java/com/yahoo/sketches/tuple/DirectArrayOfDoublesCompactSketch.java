/*
 * Copyright 2015-16, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.tuple;

import static com.yahoo.sketches.Util.DEFAULT_UPDATE_SEED;

import java.nio.ByteOrder;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.SketchesArgumentException;

/**
 * Direct Compact Sketch of type ArrayOfDoubles.
 * <p>This implementation uses data in a given Memory that is owned and managed by the caller.
 * This Memory can be off-heap, which if managed properly will greatly reduce the need for
 * the JVM to perform garbage collection.</p>
 */
final class DirectArrayOfDoublesCompactSketch extends ArrayOfDoublesCompactSketch {

  // this value exists only on heap, never serialized
  private Memory mem_;

  /**
   * Converts the given UpdatableArrayOfDoublesSketch to this compact form.
   * @param sketch the given UpdatableArrayOfDoublesSketch
   * @param dstMem the given destination Memory.
   */
  DirectArrayOfDoublesCompactSketch(final ArrayOfDoublesUpdatableSketch sketch,
      final Memory dstMem) {
    super(sketch.getNumValues());
    checkIfEnoughMemory(dstMem, sketch.getRetainedEntries(), sketch.getNumValues());
    mem_ = dstMem;
    mem_.putByte(PREAMBLE_LONGS_BYTE, (byte) 1);
    mem_.putByte(SERIAL_VERSION_BYTE, serialVersionUID);
    mem_.putByte(FAMILY_ID_BYTE, (byte) Family.TUPLE.getID());
    mem_.putByte(SKETCH_TYPE_BYTE, (byte)
        SerializerDeserializer.SketchType.ArrayOfDoublesCompactSketch.ordinal());
    final boolean isBigEndian = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN);
    isEmpty_ = sketch.isEmpty();
    final int count = sketch.getRetainedEntries();
    mem_.putByte(FLAGS_BYTE, (byte) (
      (isBigEndian ? 1 << Flags.IS_BIG_ENDIAN.ordinal() : 0)
      | (isEmpty_ ? 1 << Flags.IS_EMPTY.ordinal() : 0)
      | (count > 0 ? 1 << Flags.HAS_ENTRIES.ordinal() : 0)
    ));
    mem_.putByte(NUM_VALUES_BYTE, (byte) numValues_);
    mem_.putShort(SEED_HASH_SHORT, Util.computeSeedHash(sketch.getSeed()));
    theta_ = sketch.getThetaLong();
    mem_.putLong(THETA_LONG, theta_);
    if (count > 0) {
      mem_.putInt(RETAINED_ENTRIES_INT, sketch.getRetainedEntries());
      int keyOffset = ENTRIES_START;
      int valuesOffset = keyOffset + SIZE_OF_KEY_BYTES * sketch.getRetainedEntries();
      final ArrayOfDoublesSketchIterator it = sketch.iterator();
      while (it.next()) {
        mem_.putLong(keyOffset, it.getKey());
        mem_.putDoubleArray(valuesOffset, it.getValues(), 0, numValues_);
        keyOffset += SIZE_OF_KEY_BYTES;
        valuesOffset += SIZE_OF_VALUE_BYTES * numValues_;
      }
    }
  }

  /*
   * Creates an instance from components
   */
  DirectArrayOfDoublesCompactSketch(final long[] keys, final double[] values, final long theta,
      final boolean isEmpty, final int numValues, final short seedHash, final Memory dstMem) {
    super(numValues);
    checkIfEnoughMemory(dstMem, values.length, numValues);
    mem_ = dstMem;
    mem_.putByte(PREAMBLE_LONGS_BYTE, (byte) 1);
    mem_.putByte(SERIAL_VERSION_BYTE, serialVersionUID);
    mem_.putByte(FAMILY_ID_BYTE, (byte) Family.TUPLE.getID());
    mem_.putByte(SKETCH_TYPE_BYTE, (byte)
        SerializerDeserializer.SketchType.ArrayOfDoublesCompactSketch.ordinal());
    final boolean isBigEndian = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN);
    isEmpty_ = isEmpty;
    final int count = keys.length;
    mem_.putByte(FLAGS_BYTE, (byte) (
      (isBigEndian ? 1 << Flags.IS_BIG_ENDIAN.ordinal() : 0)
      | (isEmpty_ ? 1 << Flags.IS_EMPTY.ordinal() : 0)
      | (count > 0 ? 1 << Flags.HAS_ENTRIES.ordinal() : 0)
    ));
    mem_.putByte(NUM_VALUES_BYTE, (byte) numValues_);
    mem_.putShort(SEED_HASH_SHORT, seedHash);
    theta_ = theta;
    mem_.putLong(THETA_LONG, theta_);
    if (count > 0) {
      mem_.putInt(RETAINED_ENTRIES_INT, count);
      mem_.putLongArray(ENTRIES_START, keys, 0, count);
      mem_.putDoubleArray(ENTRIES_START + SIZE_OF_KEY_BYTES * count, values, 0, values.length);
    }
  }

  /**
   * Wraps the given Memory.
   * @param mem <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   */
  DirectArrayOfDoublesCompactSketch(final Memory mem) {
    this(mem, DEFAULT_UPDATE_SEED);
  }

  /**
   * Wraps the given Memory.
   * @param mem <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See seed</a>
   */
  DirectArrayOfDoublesCompactSketch(final Memory mem, final long seed) {
    super(mem.getByte(NUM_VALUES_BYTE));
    mem_ = mem;
    SerializerDeserializer.validateFamily(mem.getByte(FAMILY_ID_BYTE),
        mem.getByte(PREAMBLE_LONGS_BYTE));
    SerializerDeserializer.validateType(mem_.getByte(SKETCH_TYPE_BYTE),
        SerializerDeserializer.SketchType.ArrayOfDoublesCompactSketch);
    final byte version = mem_.getByte(SERIAL_VERSION_BYTE);
    if (version != serialVersionUID) {
      throw new SketchesArgumentException("Serial version mismatch. Expected: " + serialVersionUID
          + ", actual: " + version);
    }
    final boolean isBigEndian =
        mem.isAllBitsSet(FLAGS_BYTE, (byte) (1 << Flags.IS_BIG_ENDIAN.ordinal()));
    if (isBigEndian ^ ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
      throw new SketchesArgumentException("Byte order mismatch");
    }
    Util.checkSeedHashes(mem.getShort(SEED_HASH_SHORT), Util.computeSeedHash(seed));
    isEmpty_ = mem_.isAnyBitsSet(FLAGS_BYTE, (byte) (1 << Flags.IS_EMPTY.ordinal()));
    theta_ = mem_.getLong(THETA_LONG);
  }

  @Override
  public int getRetainedEntries() {
    final boolean hasEntries =
        mem_.isAnyBitsSet(FLAGS_BYTE, (byte) (1 << Flags.HAS_ENTRIES.ordinal()));
    return (hasEntries ? mem_.getInt(RETAINED_ENTRIES_INT) : 0);
  }

  @Override
  public double[][] getValues() {
    final int count = getRetainedEntries();
    final double[][] values = new double[count][];
    if (count > 0) {
      int valuesOffset = ENTRIES_START + SIZE_OF_KEY_BYTES * count;
      for (int i = 0; i < count; i++) {
        final double[] array = new double[numValues_];
        mem_.getDoubleArray(valuesOffset, array, 0, numValues_);
        values[i] = array;
        valuesOffset += SIZE_OF_VALUE_BYTES * numValues_;
      }
    }
    return values;
  }

  @Override
  public byte[] toByteArray() {
    final int count = getRetainedEntries();
    int sizeBytes = EMPTY_SIZE;
    if (count > 0) {
      sizeBytes = ENTRIES_START + SIZE_OF_KEY_BYTES * count
          + SIZE_OF_VALUE_BYTES * count * numValues_;
    }
    final byte[] byteArray = new byte[sizeBytes];
    final Memory mem = new NativeMemory(byteArray);
    mem_.copy(0, mem, 0, sizeBytes);
    return byteArray;
  }

  @Override
  public ArrayOfDoublesSketchIterator iterator() {
    return new DirectArrayOfDoublesSketchIterator(
        mem_, ENTRIES_START, getRetainedEntries(), numValues_);
  }

  @Override
  short getSeedHash() {
    return mem_.getShort(SEED_HASH_SHORT);
  }

  private static void checkIfEnoughMemory(final Memory mem, final int numEntries,
      final int numValues) {
    final int sizeNeeded =
        ENTRIES_START + (SIZE_OF_KEY_BYTES + SIZE_OF_VALUE_BYTES * numValues) * numEntries;
    if (sizeNeeded > mem.getCapacity()) {
      throw new SketchesArgumentException("Not enough memory: need " + sizeNeeded
          + " bytes, got " + mem.getCapacity() + " bytes");
    }
  }

}
