package com.yahoo.sketches.quantiles;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;

public class DoublesSketchTest {

  @Test
  public void heapToDirect() {
    UpdateDoublesSketch heapSketch = DoublesSketch.builder().build();
    for (int i = 0; i < 1000; i++) {
      heapSketch.update(i);
    }
    DoublesSketch directSketch = DoublesSketch.wrap(new NativeMemory(heapSketch.toByteArray(false)));

    assertEquals(directSketch.getMinValue(), 0.0);
    assertEquals(directSketch.getMaxValue(), 999.0);
    assertEquals(directSketch.getQuantile(0.5), 500.0, 4.0);
  }

  @Test
  public void directToHeap() {
    int sizeBytes = 10000;
    UpdateDoublesSketch directSketch = DoublesSketch.builder().initMemory(new NativeMemory(new byte[sizeBytes])).build();
    for (int i = 0; i < 1000; i++) {
      directSketch.update(i);
    }
    UpdateDoublesSketch heapSketch;
    heapSketch = (UpdateDoublesSketch) DoublesSketch.heapify(new NativeMemory(directSketch.toByteArray()));
    for (int i = 0; i < 1000; i++) {
      heapSketch.update(i + 1000);
    }
    assertEquals(heapSketch.getMinValue(), 0.0);
    assertEquals(heapSketch.getMaxValue(), 1999.0);
    assertEquals(heapSketch.getQuantile(0.5), 1000.0, 10.0);
  }

  @Test
  public void checkToByteArray() {
    UpdateDoublesSketch ds = DoublesSketch.builder().build(); //k = 128
    ds.update(1);
    ds.update(2);
    byte[] arr = ds.toByteArray(false);
    assertEquals(arr.length, ds.getUpdatableStorageBytes());
  }

  /**
   * Checks 2 DoublesSketches for equality, triggering an assert if unequal. Handles all
   * input sketches and compares only values on valid levels, allowing it to be used to compare
   * Update and Compact sketches.
   * @param sketch1 input sketch 1
   * @param sketch2 input sketch 2
   */
  static void testSketchEquality(final DoublesSketch sketch1,
                                 final DoublesSketch sketch2) {
    assertEquals(sketch1.getK(), sketch2.getK());
    assertEquals(sketch1.getN(), sketch2.getN());
    assertEquals(sketch1.getBitPattern(), sketch2.getBitPattern());
    assertEquals(sketch1.getMinValue(), sketch2.getMinValue());
    assertEquals(sketch1.getMaxValue(), sketch2.getMaxValue());

    final DoublesSketchAccessor accessor1 = DoublesSketchAccessor.wrap(sketch1);
    final DoublesSketchAccessor accessor2 = DoublesSketchAccessor.wrap(sketch2);

    // Compare base buffers. Already confirmed n and k match.
    for (int i = 0; i < accessor1.numItems(); ++i) {
      assertEquals(accessor1.get(i), accessor2.get(i));
    }

    // Iterate over levels comparing items
    long bitPattern = sketch1.getBitPattern();
    for (int lvl = 0; bitPattern != 0; ++lvl, bitPattern >>>= 1) {
      if ((bitPattern & 1) > 0) {
        accessor1.setLevel(lvl);
        accessor2.setLevel(lvl);
        for (int i = 0; i < accessor1.numItems(); ++i) {
          assertEquals(accessor1.get(i), accessor2.get(i));
        }
      }
    }
  }

  @Test
  public void checkIsSameResource() {
    int k = 16;
    Memory mem = new NativeMemory(new byte[(k*16) +24]);
    Memory cmem = new NativeMemory (new byte[8]);
    DirectUpdateDoublesSketch duds =
            (DirectUpdateDoublesSketch) DoublesSketch.builder().initMemory(mem).build(k);
    assertTrue(duds.isSameResource(mem));
    DirectCompactDoublesSketch dcds = (DirectCompactDoublesSketch) duds.compact(cmem);
    assertTrue(dcds.isSameResource(cmem));
  }

  @Test
  public void printlnTest() {
    println("PRINTING: " + this.getClass().getName());
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    //System.out.println(s); //disable here
  }

}
