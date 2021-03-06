/*
 * Copyright 2015-16, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.quantiles;

import static com.yahoo.sketches.quantiles.Util.LS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.ByteBuffer;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.SketchesArgumentException;

public class DirectCompactDoublesSketchTest {
  @BeforeMethod
  public void setUp() {
    DoublesSketch.rand.setSeed(32749); // make sketches deterministic for testing
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void wrapFromUpdateSketch() {
    final int k = 4;
    final int n = 27;
    final UpdateDoublesSketch qs = HeapUpdateDoublesSketchTest.buildAndLoadQS(k, n);

    final byte[] qsBytes = qs.toByteArray();
    final Memory qsMem = new NativeMemory(qsBytes);

    DirectCompactDoublesSketch.wrapInstance(qsMem);
    fail();
  }

  @Test
  public void createFromUnsortedUpdateSketch() {
    final int k = 4;
    final int n = 13;
    final UpdateDoublesSketch qs = DoublesSketch.builder().build(k);
    for (int i = n; i > 0; --i) {
      qs.update(i);
    }
    final Memory dstMem = new NativeMemory(new byte[qs.getCompactStorageBytes()]);
    final DirectCompactDoublesSketch compactQs
            = DirectCompactDoublesSketch.createFromUpdateSketch(qs, dstMem);

    // don't expect equal but new base buffer should be sorted
    final double[] combinedBuffer = compactQs.getCombinedBuffer();
    final int bbCount = compactQs.getBaseBufferCount();

    for (int i = 1; i < bbCount; ++i) {
      assert combinedBuffer[i - 1] < combinedBuffer[i];
    }
  }

  @Test
  public void wrapFromCompactSketch() {
    final int k = 8;
    final int n = 177;
    final DirectCompactDoublesSketch qs = buildAndLoadDCQS(k, n); // assuming ordered inserts

    final byte[] qsBytes = qs.toByteArray();
    final Memory qsMem = new NativeMemory(qsBytes);

    final DirectCompactDoublesSketch compactQs = DirectCompactDoublesSketch.wrapInstance(qsMem);
    DoublesSketchTest.testSketchEquality(qs, compactQs);
    assertEquals(qsBytes.length, compactQs.getStorageBytes());

    final double[] combinedBuffer = compactQs.getCombinedBuffer();
    assertEquals(combinedBuffer.length, compactQs.getCombinedBufferItemCapacity());
  }

  @Test
  public void wrapEmptyCompactSketch() {
    final CompactDoublesSketch s1 = DoublesSketch.builder().build().compact();
    final Memory mem
            = NativeMemory.wrap(ByteBuffer.wrap(s1.toByteArray()));
    final DoublesSketch s2 = DoublesSketch.wrap(mem);
    assertTrue(s2.isEmpty());
    assertEquals(s2.getN(), 0);
    assertEquals(s2.getMinValue(), Double.POSITIVE_INFINITY);
    assertEquals(s2.getMaxValue(), Double.NEGATIVE_INFINITY);
  }

  @Test
  public void checkEmpty() {
    final int k = PreambleUtil.DEFAULT_K;
    final DirectCompactDoublesSketch qs1 = buildAndLoadDCQS(k, 0);
    assertEquals(qs1.getQuantile(0.0), Double.POSITIVE_INFINITY);
    assertEquals(qs1.getQuantile(1.0), Double.NEGATIVE_INFINITY);
    assertEquals(qs1.getQuantile(0.5), Double.NaN);
    final double[] quantiles = qs1.getQuantiles(new double[] {0.0, 0.5, 1.0});
    assertEquals(quantiles.length, 3);
    assertEquals(quantiles[0], Double.POSITIVE_INFINITY);
    assertEquals(quantiles[1], Double.NaN);
    assertEquals(quantiles[2], Double.NEGATIVE_INFINITY);

    final double[] combinedBuffer = qs1.getCombinedBuffer();
    assertEquals(combinedBuffer.length, 2 * k);
    assertNotEquals(combinedBuffer.length, qs1.getCombinedBufferItemCapacity());
  }

  @Test
  public void checkCheckDirectMemCapacity() {
    final int k = 128;
    DirectCompactDoublesSketch.checkDirectMemCapacity(k, 2 * k - 1, (4 + 2 * k) * 8);
    DirectCompactDoublesSketch.checkDirectMemCapacity(k, 2 * k + 1, (4 + 3 * k) * 8);
    DirectCompactDoublesSketch.checkDirectMemCapacity(k, 0, 8);

    try {
      DirectCompactDoublesSketch.checkDirectMemCapacity(k, 10000, 64);
      fail();
    } catch (final SketchesArgumentException e) {
      // expected
    }
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkMemTooSmall() {
    final Memory mem = new NativeMemory(new byte[7]);
    HeapCompactDoublesSketch.heapifyInstance(mem);
  }

  static DirectCompactDoublesSketch buildAndLoadDCQS(final int k, final int n) {
    return buildAndLoadDCQS(k, n, 0);
  }

  static DirectCompactDoublesSketch buildAndLoadDCQS(final int k, final int n, final int startV) {
    final UpdateDoublesSketch qs = DoublesSketch.builder().build(k);
    for (int i = 1; i <= n; i++) {
      qs.update(startV + i);
    }
    final byte[] byteArr = new byte[qs.getCompactStorageBytes()];
    final NativeMemory mem = new NativeMemory(byteArr);
    return (DirectCompactDoublesSketch) qs.compact(mem);
  }

  @Test
  public void printlnTest() {
    println("PRINTING: " + this.getClass().getName());
    print("PRINTING: " + this.getClass().getName() + LS);
  }

  static void println(final String s) {
    print(s + LS);
  }

  /**
   * @param s value to print
   */
  static void print(final String s) {
    //System.err.print(s); //disable here
  }
}
