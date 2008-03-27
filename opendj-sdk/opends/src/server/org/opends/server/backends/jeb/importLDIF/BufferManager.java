/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2008 Sun Microsystems, Inc.
 */


package org.opends.server.backends.jeb.importLDIF;

import org.opends.server.types.Entry;
import org.opends.server.backends.jeb.Index;
import org.opends.server.backends.jeb.EntryID;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.dbi.MemoryBudget;
import static org.opends.server.loggers.ErrorLogger.logError;
import org.opends.messages.Message;
import static org.opends.messages.JebMessages.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;


/**
 * Manages a shared cache among worker threads that caches substring
 * key/value pairs to avoid DB cache access. Once the cache is above it's
 * memory usage limit, it will start slowly flushing keys (similar to the
 * JEB eviction process) until it is under the limit.
 */

public class BufferManager {

  final static int MIN_FLUSH_THREAD_NUM = 2;

  //Lock used by the flush condition.
  final Lock lock = new ReentrantLock();

  //Used to block flush threads until all have completed any work on the
  //element map.
  final Condition flushCond = lock.newCondition();

  //Number of flush flushWaiters waiting to flush.
  private int flushWaiters = 0;

  //Memory usage counter.
  private long memoryUsage=0;

  //Memory limit.
  private long memoryLimit;

  //Next element in the cache to start flushing at during next flushAll cycle.
  private KeyHashElement nextElem;

  //Extra bytes to flushAll.
  private final int extraBytes  = 1024 * 1024;

  //Counters for statistics, total is number of accesses, hit is number of
  //keys found in cache.
  private long total=0, hit=0;

  //Actual map used to buffer keys.
  private TreeMap<KeyHashElement, KeyHashElement> elementMap =
                        new TreeMap<KeyHashElement, KeyHashElement>();

  //Overhead values determined from using JHAT. They appear to be the same
  //for both 32 and 64 bit machines. Close enough.
  private final static int TREEMAP_ENTRY_OVERHEAD = 29;
  private final static int KEY_ELEMENT_OVERHEAD = 28;

  //Count of number of worker threads allowed to flush at the end of the run.
  private int flushThreadNumber;

  //Used to prevent memory flush
  private boolean limitFlush = true;


  /**
   * Create buffer manager instance.
   *
   * @param memoryLimit The memory limit.
   * @param importThreadCount  The count of import worker threads.
   */
  public BufferManager(long memoryLimit, int importThreadCount) {
    this.memoryLimit = memoryLimit;
    this.nextElem = null;
    //This limits the number of flush threads to 10 or less.
    if(importThreadCount > MIN_FLUSH_THREAD_NUM)
     this.flushThreadNumber = MIN_FLUSH_THREAD_NUM;
    else
      this.flushThreadNumber = importThreadCount;
System.out.println("Num: " + flushThreadNumber);
  }

  /**
   * Insert an entry ID into the buffer using the both the specified index and
   * entry to build a key set. Will flush the buffer if over the memory limit.
   *
   * @param index  The index to use.
   * @param entry The entry used to build the key set.
   * @param entryID The entry ID to insert into the key set.
   * @param txn A transaction.
   * @throws DatabaseException If a problem happened during a flushAll cycle.
   */
  void insert(Index index, Entry entry,
                     EntryID entryID, Transaction txn)
  throws DatabaseException {
     int entryLimit = index.getIndexEntryLimit();
     Set<byte[]> keySet = new HashSet<byte[]>();
     index.indexer.indexEntry(txn, entry, keySet);
    synchronized(elementMap) {
       for(byte[] key : keySet) {
         KeyHashElement elem = new KeyHashElement(key, index, entryID);
         total++;
         if(!elementMap.containsKey(elem)) {
            elementMap.put(elem, elem);
            memoryUsage += TREEMAP_ENTRY_OVERHEAD + elem.getMemorySize();
         } else {
           KeyHashElement curElem = elementMap.get(elem);
           if(curElem.isDefined()) {
            int oldSize = curElem.getMemorySize();
            curElem.addEntryID(entryID, entryLimit);
            int newSize = curElem.getMemorySize();
            //Might have moved from defined to undefined.
            memoryUsage += (newSize - oldSize);
            hit++;
           }
         }
       }
       //If over the memory limit and import hasn't completed
      //flush some keys from the cache to make room.
       if((memoryUsage > memoryLimit) && limitFlush) {
         flushUntilUnderLimit();
       }
    }
  }

  /**
   * Flush the buffer to DB until the buffer is under the memory limit.
   *
   * @throws DatabaseException If a problem happens during an index insert.
   */
  private void flushUntilUnderLimit() throws DatabaseException {
    Iterator<KeyHashElement> iter;
    if(nextElem == null) {
      iter = elementMap.keySet().iterator();
    } else {
      iter = elementMap.tailMap(nextElem).keySet().iterator();
    }
    while(((memoryUsage + extraBytes) > memoryLimit) && limitFlush) {
      if(iter.hasNext()) {
        KeyHashElement curElem = iter.next();
        //Never flush undefined elements.
        if(curElem.isDefined()) {
          Index index = curElem.getIndex();
          index.insert(null, new DatabaseEntry(curElem.getKey()),
                  curElem.getIDSet());
          memoryUsage -= TREEMAP_ENTRY_OVERHEAD + curElem.getMemorySize();
          if(limitFlush) {
            iter.remove();
          }
        }
      } else {
        //Wrapped around, start at the first element.
        nextElem = elementMap.firstKey();
        iter = elementMap.keySet().iterator();
      }
    }
    //Start at this element next flushAll cycle.
    nextElem = iter.next();
  }

  /**
   * Called from main thread to prepare for final buffer flush at end of
   * ldif load.
   */
  void prepareFlush() {
    limitFlush=false;
    Message msg =
           NOTE_JEB_IMPORT_LDIF_BUFFER_FLUSH.get(elementMap.size(), total, hit);
    logError(msg);
  }

  /**
   * Writes all of the buffer elements to DB. The specific id is used to
   * share the buffer among the worker threads so this function can be
   * multi-threaded.
   *
   * @param id The thread id.
   * @throws DatabaseException If an error occurred during the insert.
   * @throws InterruptedException If a thread has been interrupted.
   */
  void flushAll(int id) throws DatabaseException, InterruptedException {
    //If the thread ID is greater than the flush thread max return.
    if(id > flushThreadNumber) {
      return;
    } else if (flushThreadNumber > 1) {
       waitToFlush();
    }
    TreeSet<KeyHashElement>  tSet =
            new TreeSet<KeyHashElement>(elementMap.keySet());
    Iterator<KeyHashElement> iter = tSet.iterator();
    int i=0;
    while(iter.hasNext()) {
      KeyHashElement curElem = iter.next();
      Index index = curElem.getIndex();
      //Each thread handles a piece of the buffer based on its thread id.
      if((i % flushThreadNumber) == id) {
        index.insert(null, new DatabaseEntry(curElem.getKey()),
                curElem.getIDSet());
      }
      i++;
    }
  }

  /**
   * Make the threads that are going to flush wait until all threads have
   * completed inserts into the element map. This prevents concurrency
   * exceptions, especially if the import has been configured for a large
   * number of threads.
   *
   * @throws InterruptedException  If the threads are interrupted.
   */
  private void waitToFlush() throws InterruptedException {
    lock.lock();
    try {
      if(flushWaiters++ < flushThreadNumber)
        flushCond.await();
      else
        flushCond.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   *  Class used to represent an element in the buffer.
   */
  class KeyHashElement implements Comparable {

    //Bytes representing the key.
    private  byte[] key;

    //Hash code returned from the System.identityHashCode method on the index
    //object.
    private int indexHashCode;

    //Index related to the element.
    private Index index;

    //The set of IDs related to the key.
    private ImportIDSet importIDSet;

    /**
     * Create instance of an element for the specified key and index, the add
     * the specified entry ID to the ID set.
     *
     * @param key The key.
     * @param index The index.
     * @param entryID The entry ID to start off with.
     */
    public KeyHashElement(byte[] key, Index index, EntryID entryID) {
      this.key = key;
      this.index = index;
      //Use the integer set for right now. This is good up to 2G number of
      //entries. There is also a LongImportSet, but it currently isn't used.
      this.importIDSet = new IntegerImportIDSet(entryID);
      //Used if there when there are conflicts if two or more indexes have
      //the same key.
      this.indexHashCode = System.identityHashCode(index);
    }

    /**
     * Add an entry ID to the set.
     *
     * @param entryID  The entry ID to add.
     * @param entryLimit The entry limit
     */
    void addEntryID(EntryID entryID, int entryLimit) {
      importIDSet.addEntryID(entryID, entryLimit);
    }

    /**
     * Return the index.
     *
     * @return The index.
     */
    Index getIndex(){
      return index;
    }

    /**
     * Return the key.
     *
     * @return The key.
     */
    byte[] getKey() {
      return key;
    }

    /**
     * Return the ID set.
      * @return The import ID set.
     */
    ImportIDSet getIDSet() {
      return importIDSet;
    }

    /**
     * Return if the ID set is defined or not.
     *
     * @return <CODE>True</CODE> if the ID set is defined.
     */
    boolean isDefined() {
      return importIDSet.isDefined();
    }

    /**
     * Compare the bytes of two keys.
     *
     * @param a  Key a.
     * @param b  Key b.
     * @return  0 if the keys are equal, -1 if key a is less than key b, 1 if
     *          key a is greater than key b.
     */
    private int compare(byte[] a, byte[] b) {
      int i;
      for (i = 0; i < a.length && i < b.length; i++) {
        if (a[i] > b[i]) {
          return 1;
        }
        else if (a[i] < b[i]) {
          return -1;
        }
      }
      if (a.length == b.length) {
        return 0;
      }
      if (a.length > b.length){
        return 1;
      }
      else {
        return -1;
      }
    }

    /**
     * Compare the specified object to the current object. If the keys are
     * equal, then the indexHashCode value is used as a tie-breaker.
     *
     * @param o The object representing a KeyHashElement.
     * @return 0 if the objects are equal, -1 if the current object is less
     *         than the specified object, 1 otherwise.
     */
    public int compareTo(Object o) {
      if (o == null) {
        throw new NullPointerException();
      }
      KeyHashElement inElem = (KeyHashElement) o;
      int keyCompare = compare(key, inElem.key);
      if(keyCompare == 0) {
        if(indexHashCode == inElem.indexHashCode) {
          return 0;
        } else if(indexHashCode < inElem.indexHashCode) {
          return -1;
        } else {
          return 1;
        }
      } else {
        return keyCompare;
      }
    }

    /**
     * Return the current total memory size of the element.
     * @return The memory size estimate of a KeyHashElement.
     */
    int getMemorySize() {
      return  KEY_ELEMENT_OVERHEAD +
              MemoryBudget.byteArraySize(key.length) +
              importIDSet.getMemorySize();
    }
  }
}
