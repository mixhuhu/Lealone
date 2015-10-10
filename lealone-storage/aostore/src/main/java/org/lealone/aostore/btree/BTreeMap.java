/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0, and the
 * EPL 1.0 (http://h2database.com/html/license.html). Initial Developer: H2
 * Group
 */
package org.lealone.aostore.btree;

import java.io.File;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.lealone.aostore.AOStore;
import org.lealone.common.message.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.storage.StorageMap;
import org.lealone.storage.StorageMapBuilder;
import org.lealone.storage.StorageMapCursor;
import org.lealone.storage.type.DataType;
import org.lealone.storage.type.ObjectDataType;

/**
 * A read optimization BTree stored map.
 * <p>
 * Read operations can happen concurrently with all other operations, without
 * risk of corruption.
 * <p>
 * Write operations first read the relevant area from disk to memory
 * concurrently, and only then modify the data. The in-memory part of write
 * operations is synchronized. For scalable concurrent in-memory write
 * operations, the map should be split into multiple smaller sub-maps that are
 * then synchronized independently.
 * 
 * @param <K> the key class
 * @param <V> the value class
 * 
 * @author H2 Group
 * @author zhh
 */
public class BTreeMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V>, StorageMap<K, V> {

    protected final ConcurrentArrayList<BTreePage> oldRoots = new ConcurrentArrayList<>();

    protected final int id;
    protected final String name;
    protected final DataType keyType;
    protected final DataType valueType;
    protected final boolean readOnly;
    protected final boolean inMemory;

    protected final Map<String, Object> config;
    protected final BTreeStore store;

    /**
     * The current root page (may not be null).
     */
    protected volatile BTreePage root;

    protected BTreeMap(String name, DataType keyType, DataType valueType, Map<String, Object> config) {
        this(name, keyType, valueType, config, null);
    }

    @SuppressWarnings("unchecked")
    protected BTreeMap(String name, DataType keyType, DataType valueType, Map<String, Object> config, BTreeStore store) {
        DataUtils.checkArgument(name != null, "The name may not be null");
        DataUtils.checkArgument(config != null, "The config may not be null");

        if (keyType == null) {
            keyType = new ObjectDataType();
        }
        if (valueType == null) {
            valueType = new ObjectDataType();
        }

        this.id = DataUtils.readHexInt(config, "id", 0);
        this.name = name;
        this.keyType = keyType;
        this.valueType = valueType;
        this.readOnly = config.containsKey("readOnly");
        this.inMemory = config.get("storeName") == null || config.containsKey("inMemory");
        this.config = config;

        if (store == null) {
            store = new BTreeStore((BTreeMap<Object, Object>) this);
        }

        this.store = store;

        if (store.lastChunk != null)
            setRootPos(store.lastChunk.rootPagePos, store.lastChunk.version);
        else
            setRootPos(0, -1);
    }

    String getBTreeStoreName() {
        if (inMemory)
            return null;
        String storeName = (String) config.get("storeName");
        return storeName + File.separator + name + AOStore.MAP_NAME_ID_SEPARATOR + id;
    }

    /**
     * Set the position of the root page.
     * 
     * @param rootPos the position, 0 for empty
     * @param version the version of the root
     */
    void setRootPos(long rootPos, long version) {
        if (rootPos == 0) {
            root = BTreePage.createEmpty(this, version);
        } else {
            root = store.readPage(rootPos);
            root.setVersion(version);
        }
    }

    /**
     * Get the map id. Please note the map id may be different after compacting
     * a store.
     * 
     * @return the map id
     */
    @Override
    public int getId() {
        return id;
    }

    /**
     * Get the map name.
     * 
     * @return the name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get the key type.
     * 
     * @return the key type
     */
    @Override
    public DataType getKeyType() {
        return keyType;
    }

    /**
     * Get the value type.
     * 
     * @return the value type
     */
    @Override
    public DataType getValueType() {
        return valueType;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Whether this is in-memory map, meaning that changes are not persisted. 
     * By default (even if the store is not persisted), maps are not in-memory.
     * 
     * @return whether this map is in-memory
     */
    @Override
    public boolean isInMemory() {
        return inMemory;
    }

    public BTreeStore getStore() {
        return store;
    }

    /**
     * Add or replace a key-value pair.
     * 
     * @param key the key (may not be null)
     * @param value the value (may not be null)
     * @return the old value if the key existed, or null otherwise
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized V put(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        beforeWrite();
        long v = store.getCurrentVersion();
        BTreePage p = root.copy(v);

        if (p.needSplit())
            p = splitRoot(p, v);

        Object result = put(p, v, key, value);
        newRoot(p);
        return (V) result;
    }

    /**
     * This method is called before writing to the map. 
     * The default implementation checks whether writing is allowed, and tries to detect
     * concurrent modification.
     * 
     * @throws UnsupportedOperationException if the map is read-only, or if
     *             another thread is concurrently writing
     */
    protected void beforeWrite() {
        if (store.isClosed()) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_CLOSED, "This map is closed");
        }
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException("This map is read-only");
        }
        store.beforeWrite();
    }

    /**
     * Split the root page.
     * 
     * @param p the page
     * @param writeVersion the write version
     * @return the new sibling
     */
    protected BTreePage splitRoot(BTreePage p, long writeVersion) {
        BTreePage oldPage = p;
        long totalCount = p.getTotalCount();
        int at = p.getKeyCount() / 2;
        Object k = p.getKey(at);
        BTreePage split = p.split(at);
        Object[] keys = { k };
        BTreePage.PageReference[] children = { new BTreePage.PageReference(p, p.getPos(), p.getTotalCount()),
                new BTreePage.PageReference(split, split.getPos(), split.getTotalCount()), };
        p = BTreePage.create(this, writeVersion, keys, null, children, totalCount, 0);
        p.setOldPos(oldPage); // 记下最初的page pos
        return p;
    }

    /**
     * Add or update a key-value pair.
     * 
     * @param p the page
     * @param writeVersion the write version
     * @param key the key (may not be null)
     * @param value the value (may not be null)
     * @return the old value, or null
     */
    protected Object put(BTreePage p, long writeVersion, Object key, Object value) {
        int index = p.binarySearch(key);
        if (p.isLeaf()) {
            if (index < 0) {
                index = -index - 1;
                p.insertLeaf(index, key, value);
                return null;
            }
            return p.setValue(index, value);
        }
        // p is a node
        if (index < 0) {
            index = -index - 1;
        } else {
            index++;
        }
        BTreePage c = p.getChildPage(index).copy(writeVersion);
        if (c.needSplit()) {
            // split on the way down
            int at = c.getKeyCount() / 2;
            Object k = c.getKey(at);
            BTreePage split = c.split(at);
            p.setChild(index, split);
            p.insertNode(index, k, c);
            // now we are not sure where to add
            return put(p, writeVersion, key, value);
        }
        Object result = put(c, writeVersion, key, value);
        p.setChild(index, c);
        return result;
    }

    /**
     * Use the new root page from now on.
     * 
     * @param newRoot the new root page
     */
    protected void newRoot(BTreePage newRoot) {
        if (root != newRoot) {
            removeUnusedOldVersions();
            if (root.getVersion() != newRoot.getVersion()) {
                BTreePage last = oldRoots.peekLast();
                if (last == null || last.getVersion() != root.getVersion()) {
                    oldRoots.add(root);
                }
            }
            root = newRoot;
        }
    }

    /**
     * Forget those old versions that are no longer needed.
     */
    void removeUnusedOldVersions() {
        long oldest = store.getOldestVersionToKeep();
        if (oldest == -1) {
            return;
        }
        BTreePage last = oldRoots.peekLast(); // 后面的是最近加入的
        while (true) {
            BTreePage p = oldRoots.peekFirst();
            if (p == null || p.getVersion() >= oldest || p == last) { // 保留最后一个
                break;
            }
            oldRoots.removeFirst(p);
        }
    }

    /**
     * Add a key-value pair if it does not yet exist.
     * 
     * @param key the key (may not be null)
     * @param value the new value
     * @return the old value if the key existed, or null otherwise
     */
    @Override
    public synchronized V putIfAbsent(K key, V value) {
        V old = get(key);
        if (old == null) {
            put(key, value);
        }
        return old;
    }

    /**
     * Get a value.
     * 
     * @param key the key
     * @return the value, or null if not found
     */
    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        return (V) binarySearch(root, key);
    }

    /**
     * Get the value for the given key, or null if not found.
     * 
     * @param p the page
     * @param key the key
     * @return the value or null
     */
    protected Object binarySearch(BTreePage p, Object key) {
        int index = p.binarySearch(key);
        if (!p.isLeaf()) {
            if (index < 0) {
                index = -index - 1;
            } else {
                index++;
            }
            p = p.getChildPage(index);
            return binarySearch(p, key);
        }
        if (index >= 0) {
            return p.getValue(index);
        }
        return null;
    }

    /**
     * Get the key at the given index.
     * <p>
     * This is a O(log(size)) operation.
     * 
     * @param index the index
     * @return the key
     */
    @Override
    @SuppressWarnings("unchecked")
    public K getKey(long index) {
        if (index < 0 || index >= size()) {
            return null;
        }
        BTreePage p = root;
        long offset = 0;
        while (true) {
            if (p.isLeaf()) {
                if (BTreePage.ASSERT) {
                    if (index >= offset + p.getKeyCount()) {
                        // return null;
                        throw DbException.throwInternalError();
                    }
                }
                return (K) p.getKey((int) (index - offset));
            }
            int i = 0, size = getChildPageCount(p);
            for (; i < size; i++) {
                long c = p.getCounts(i);
                if (index < c + offset) {
                    break;
                }
                offset += c;
            }
            if (BTreePage.ASSERT) {
                if (i == size) {
                    // return null;
                    throw DbException.throwInternalError();
                }
            }
            p = p.getChildPage(i);
        }
    }

    /**
     * Get the index of the given key in the map.
     * <p>
     * This is a O(log(size)) operation.
     * <p>
     * If the key was found, the returned value is the index in the key array.
     * If not found, the returned value is negative, where -1 means the provided
     * key is smaller than any keys. See also Arrays.binarySearch.
     * 
     * @param key the key
     * @return the index
     */
    @Override
    public long getKeyIndex(K key) {
        if (size() == 0) {
            return -1;
        }
        BTreePage p = root;
        long offset = 0;
        while (true) {
            int x = p.binarySearch(key);
            if (p.isLeaf()) {
                if (x < 0) {
                    return -offset + x;
                }
                return offset + x;
            }
            if (x < 0) {
                x = -x - 1;
            } else {
                x++;
            }
            for (int i = 0; i < x; i++) {
                offset += p.getCounts(i);
            }
            p = p.getChildPage(x);
        }
    }

    /**
     * Get the key list. The list is a read-only representation of all keys.
     * <p>
     * The get and indexOf methods are O(log(size)) operations. The result of
     * indexOf is cast to an int.
     * 
     * @return the key list
     */
    public List<K> keyList() {
        return new AbstractList<K>() {

            @Override
            public K get(int index) {
                return getKey(index);
            }

            @Override
            public int size() {
                return BTreeMap.this.size();
            }

            @Override
            @SuppressWarnings("unchecked")
            public int indexOf(Object key) {
                return (int) getKeyIndex((K) key);
            }

        };
    }

    /**
     * Get the first key, or null if the map is empty.
     * 
     * @return the first key, or null
     */
    @Override
    public K firstKey() {
        return getFirstLast(true);
    }

    /**
     * Get the last key, or null if the map is empty.
     * 
     * @return the last key, or null
     */
    @Override
    public K lastKey() {
        return getFirstLast(false);
    }

    /**
     * Get the first (lowest) or last (largest) key.
     * 
     * @param first whether to retrieve the first key
     * @return the key, or null if the map is empty
     */
    @SuppressWarnings("unchecked")
    protected K getFirstLast(boolean first) {
        if (size() == 0) {
            return null;
        }
        BTreePage p = root;
        while (true) {
            if (p.isLeaf()) {
                return (K) p.getKey(first ? 0 : p.getKeyCount() - 1);
            }
            p = p.getChildPage(first ? 0 : getChildPageCount(p) - 1);
        }
    }

    /**
     * Get the smallest key that is larger than the given key, or null if no
     * such key exists.
     * 
     * @param key the key
     * @return the result
     */
    @Override
    public K higherKey(K key) {
        return getMinMax(key, false, true);
    }

    /**
     * Get the smallest key that is larger or equal to this key.
     * 
     * @param key the key
     * @return the result
     */
    @Override
    public K ceilingKey(K key) {
        return getMinMax(key, false, false);
    }

    /**
     * Get the largest key that is smaller than the given key, or null if no
     * such key exists.
     * 
     * @param key the key
     * @return the result
     */
    @Override
    public K lowerKey(K key) {
        return getMinMax(key, true, true);
    }

    /**
     * Get the largest key that is smaller or equal to this key.
     * 
     * @param key the key
     * @return the result
     */
    @Override
    public K floorKey(K key) {
        return getMinMax(key, true, false);
    }

    /**
     * Get the smallest or largest key using the given bounds.
     * 
     * @param key the key
     * @param min whether to retrieve the smallest key
     * @param excluding if the given upper/lower bound is exclusive
     * @return the key, or null if no such key exists
     */
    protected K getMinMax(K key, boolean min, boolean excluding) {
        return getMinMax(root, key, min, excluding);
    }

    @SuppressWarnings("unchecked")
    private K getMinMax(BTreePage p, K key, boolean min, boolean excluding) {
        if (p.isLeaf()) {
            int x = p.binarySearch(key);
            if (x < 0) {
                x = -x - (min ? 2 : 1);
            } else if (excluding) {
                x += min ? -1 : 1;
            }
            if (x < 0 || x >= p.getKeyCount()) {
                return null;
            }
            return (K) p.getKey(x);
        }
        int x = p.binarySearch(key);
        if (x < 0) {
            x = -x - 1;
        } else {
            x++;
        }
        while (true) {
            if (x < 0 || x >= getChildPageCount(p)) {
                return null;
            }
            K k = getMinMax(p.getChildPage(x), key, min, excluding);
            if (k != null) {
                return k;
            }
            x += min ? -1 : 1;
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    /**
     * Remove all entries.
     */
    @Override
    public synchronized void clear() {
        beforeWrite();
        // TODO 如何跟踪被删除的page pos
        root.removeAllRecursive();
        newRoot(BTreePage.createEmpty(this, store.getCurrentVersion()));
    }

    /**
     * Close the map. Accessing the data is still possible (to allow concurrent
     * reads), but it is marked as closed.
     */
    @Override
    public void close() {
        store.close();
    }

    @Override
    public boolean isClosed() {
        return store.isClosed();
    }

    /**
     * Check whether the two values are equal.
     * 
     * @param a the first value
     * @param b the second value
     * @return true if they are equal
     */
    @Override
    public boolean areValuesEqual(Object a, Object b) {
        return areValuesEqual(valueType, a, b);
    }

    public static boolean areValuesEqual(DataType valueType, Object a, Object b) {
        if (a == b) {
            return true;
        } else if (a == null || b == null) {
            return false;
        }
        return valueType.compare(a, b) == 0;
    }

    /**
     * Replace a value for an existing key, if the value matches.
     * 
     * @param key the key (may not be null)
     * @param oldValue the expected value
     * @param newValue the new value
     * @return true if the value was replaced
     */
    @Override
    public synchronized boolean replace(K key, V oldValue, V newValue) {
        V old = get(key);
        if (areValuesEqual(old, oldValue)) {
            put(key, newValue);
            return true;
        }
        return false;
    }

    /**
     * Replace a value for an existing key.
     * 
     * @param key the key (may not be null)
     * @param value the new value
     * @return the old value, if the value was replaced, or null
     */
    @Override
    public synchronized V replace(K key, V value) {
        V old = get(key);
        if (old != null) {
            put(key, value);
            return old;
        }
        return null;
    }

    /**
     * Remove a key-value pair, if the key exists.
     * 
     * @param key the key (may not be null)
     * @return the old value if the key existed, or null otherwise
     */
    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        beforeWrite();
        V result = get(key);
        if (result == null) {
            return null;
        }
        long v = store.getCurrentVersion();
        synchronized (this) {
            BTreePage p = root.copy(v);
            result = (V) remove(p, v, key);
            if (!p.isLeaf() && p.getTotalCount() == 0) {
                p.removePage();
                p = BTreePage.createEmpty(this, p.getVersion());
            }
            newRoot(p);
        }
        return result;
    }

    /**
     * Remove a key-value pair if the value matches the stored one.
     * 
     * @param key the key (may not be null)
     * @param value the expected value
     * @return true if the item was removed
     */
    @Override
    public synchronized boolean remove(Object key, Object value) {
        V old = get(key);
        if (areValuesEqual(old, value)) {
            remove(key);
            return true;
        }
        return false;
    }

    /**
     * Remove a key-value pair.
     * 
     * @param p the page (may not be null)
     * @param writeVersion the write version
     * @param key the key
     * @return the old value, or null if the key did not exist
     */
    protected Object remove(BTreePage p, long writeVersion, Object key) {
        int index = p.binarySearch(key);
        Object result = null;
        if (p.isLeaf()) {
            if (index >= 0) {
                result = p.getValue(index);
                p.remove(index);
            }
            return result;
        }
        // node
        if (index < 0) {
            index = -index - 1;
        } else {
            index++;
        }
        BTreePage cOld = p.getChildPage(index);
        BTreePage c = cOld.copy(writeVersion);
        result = remove(c, writeVersion, key);
        if (result == null || c.getTotalCount() != 0) {
            // no change, or
            // there are more nodes
            p.setChild(index, c);
        } else {
            // this child was deleted
            if (p.getKeyCount() == 0) {
                p.setChild(index, c);
                c.removePage();
            } else {
                p.remove(index);
            }
        }
        return result;
    }

    /**
     * Iterate over a number of keys.
     * 
     * @param from the first key to return
     * @return the iterator
     */
    public Iterator<K> keyIterator(K from) {
        return new BTreeCursor<>(this, root, from);
    }

    /**
     * Get a cursor to iterate over a number of keys and values.
     * 
     * @param from the first key to return
     * @return the cursor
     */
    @Override
    public StorageMapCursor<K, V> cursor(K from) {
        return new BTreeCursor<>(this, root, from);
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        final BTreeMap<K, V> map = this;
        final BTreePage root = this.root;
        return new AbstractSet<Entry<K, V>>() {

            @Override
            public Iterator<Entry<K, V>> iterator() {
                final StorageMapCursor<K, V> cursor = new BTreeCursor<>(map, root, null);
                return new Iterator<Entry<K, V>>() {

                    @Override
                    public boolean hasNext() {
                        return cursor.hasNext();
                    }

                    @Override
                    public Entry<K, V> next() {
                        K k = cursor.next();
                        return new DataUtils.MapEntry<K, V>(k, cursor.getValue());
                    }

                    @Override
                    public void remove() {
                        throw DataUtils.newUnsupportedOperationException("Removing is not supported");
                    }
                };

            }

            @Override
            public int size() {
                return BTreeMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return BTreeMap.this.containsKey(o);
            }

        };

    }

    @Override
    public Set<K> keySet() {
        final BTreeMap<K, V> map = this;
        final BTreePage root = this.root;
        return new AbstractSet<K>() {

            @Override
            public Iterator<K> iterator() {
                return new BTreeCursor<>(map, root, null);
            }

            @Override
            public int size() {
                return BTreeMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return BTreeMap.this.containsKey(o);
            }

        };
    }

    /**
     * Get the number of entries, as a integer. Integer.MAX_VALUE is returned if
     * there are more than this entries.
     * 
     * @return the number of entries, as an integer
     */
    @Override
    public int size() {
        long size = sizeAsLong();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    /**
     * Get the number of entries, as a long.
     * 
     * @return the number of entries
     */
    @Override
    public long sizeAsLong() {
        return root.getTotalCount();
    }

    @Override
    public boolean isEmpty() {
        // could also use (sizeAsLong() == 0)
        return root.isLeaf() && root.getKeyCount() == 0;
    }

    /**
     * Open an old version for the given map.
     * 
     * @param version the version
     * @return the map
     */
    public BTreeMap<K, V> openVersion(long version) {
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException("This map is read-only; need to call "
                    + "the method on the writable map");
        }
        DataUtils.checkArgument(version >= store.createVersion,
                "Unknown version {0}; this map was created in version is {1}", version, store.createVersion);
        BTreePage newest = null;
        // need to copy because it can change
        BTreePage r = root;
        if (version >= r.getVersion()
                && (version == store.getCurrentVersion() || version == store.createVersion || r.getVersion() >= 0)) {
            newest = r;
        } else {
            BTreePage last = oldRoots.peekFirst();
            if (last == null || version < last.getVersion()) {
                // smaller than all in-memory versions
                return store.openMapVersion(version);
            }
            Iterator<BTreePage> it = oldRoots.iterator();
            while (it.hasNext()) {
                BTreePage p = it.next();
                if (p.getVersion() > version) {
                    break;
                }
                last = p;
            }
            newest = last;
        }
        BTreeMap<K, V> m = openReadOnly();
        m.root = newest;
        return m;
    }

    /**
     * Open a copy of the map in read-only mode.
     * 
     * @return the opened map
     */
    BTreeMap<K, V> openReadOnly() {
        HashMap<String, Object> c = new HashMap<>(config);
        c.put("id", id);
        c.put("readOnly", 1);

        BTreeMap<K, V> m = new BTreeMap<>(name, keyType, valueType, c, store);
        m.root = root;
        return m;
    }

    public long getVersion() {
        return root.getVersion();
    }

    /**
     * Get the child page count for this page. This is to allow another map
     * implementation to override the default, in case the last child is not to
     * be used.
     * 
     * @param p the page
     * @return the number of direct children
     */
    protected int getChildPageCount(BTreePage p) {
        return p.getRawChildPageCount();
    }

    /**
     * Get the map type. When opening an existing map, the map type must match.
     * 
     * @return the map type
     */
    public String getType() {
        return "BTree";
    }

    /**
     * Rollback to the given version.
     * 
     * @param version the version
     */
    void internalRollbackTo(long version) {
        beforeWrite();
        if (version <= store.createVersion) {
            // the map is removed later
        } else if (root.getVersion() >= version) {
            while (true) {
                BTreePage last = oldRoots.peekLast();
                if (last == null) {
                    break;
                }
                // slow, but rollback is not a common operation
                oldRoots.removeLast(last);
                root = last;
                if (root.getVersion() < version) {
                    break;
                }
            }
        }
    }

    /**
     * Re-write any pages that belong to one of the chunks in the given set.
     * 
     * @param set the set of chunk ids
     * @return whether rewriting was successful
     */
    boolean rewrite(Set<Integer> set) {
        // read from old version, to avoid concurrent reads
        long previousVersion = store.getCurrentVersion() - 1;
        if (previousVersion < store.createVersion) {
            // a new map
            return true;
        }
        BTreeMap<K, V> readMap;
        try {
            readMap = openVersion(previousVersion);
        } catch (IllegalArgumentException e) {
            // unknown version: ok
            // TODO should not rely on exception handling
            return true;
        }
        try {
            rewrite(readMap.root, set);
            return true;
        } catch (IllegalStateException e) {
            // TODO should not rely on exception handling
            if (DataUtils.getErrorCode(e.getMessage()) == DataUtils.ERROR_CHUNK_NOT_FOUND) {
                // ignore
                return false;
            }
            throw e;
        }
    }

    private int rewrite(BTreePage p, Set<Integer> set) {
        if (p.isLeaf()) {
            long pos = p.getPos();
            int chunkId = DataUtils.getPageChunkId(pos);
            if (!set.contains(chunkId)) {
                return 0;
            }
            if (p.getKeyCount() > 0) {
                @SuppressWarnings("unchecked")
                K key = (K) p.getKey(0);
                V value = get(key);
                if (value != null) {
                    replace(key, value, value);
                }
            }
            return 1;
        }
        int writtenPageCount = 0;
        for (int i = 0; i < getChildPageCount(p); i++) {
            long childPos = p.getChildPagePos(i);
            if (childPos != 0 && DataUtils.getPageType(childPos) == DataUtils.PAGE_TYPE_LEAF) {
                // we would need to load the page, and it's a leaf:
                // only do that if it's within the set of chunks we are
                // interested in
                int chunkId = DataUtils.getPageChunkId(childPos);
                if (!set.contains(chunkId)) {
                    continue;
                }
            }
            writtenPageCount += rewrite(p.getChildPage(i), set);
        }
        if (writtenPageCount == 0) {
            long pos = p.getPos();
            int chunkId = DataUtils.getPageChunkId(pos);
            if (set.contains(chunkId)) {
                // an inner node page that is in one of the chunks,
                // but only points to chunks that are not in the set:
                // if no child was changed, we need to do that now
                // (this is not needed if anyway one of the children
                // was changed, as this would have updated this
                // page as well)
                BTreePage p2 = p;
                while (!p2.isLeaf()) {
                    p2 = p2.getChildPage(0);
                }
                @SuppressWarnings("unchecked")
                K key = (K) p2.getKey(0);
                V value = get(key);
                if (value != null) {
                    replace(key, value, value);
                }
                writtenPageCount++;
            }
        }
        return writtenPageCount;
    }

    // /**
    // * Copy a map. All pages are copied.
    // *
    // * @param sourceMap the source map
    // */
    // public void copyFrom(BTreeMap<K, V> sourceMap) {
    // beforeWrite();
    // newRoot(copy(sourceMap.root, null));
    // }
    //
    // private BTreePage copy(BTreePage source, CursorPos parent) {
    // BTreePage target = BTreePage.create(this, store.getCurrentVersion(), source);
    // if (source.isLeaf()) {
    // BTreePage child = target;
    // for (CursorPos p = parent; p != null; p = p.parent) {
    // p.page.setChild(p.index, child);
    // p.page = p.page.copy(store.getCurrentVersion());
    // child = p.page;
    // if (p.parent == null) {
    // newRoot(p.page);
    // beforeWrite();
    // }
    // }
    // } else {
    // // temporarily, replace child pages with empty pages,
    // // to ensure there are no links to the old store
    // for (int i = 0; i < getChildPageCount(target); i++) {
    // target.setChild(i, null);
    // }
    // CursorPos pos = new CursorPos(target, 0, parent);
    // for (int i = 0; i < getChildPageCount(target); i++) {
    // pos.index = i;
    // long p = source.getChildPagePos(i);
    // if (p != 0) {
    // // p == 0 means no child
    // // (for example the last entry of an r-tree node)
    // // (the MVMap is also used for r-trees for compacting)
    // copy(source.getChildPage(i), pos);
    // }
    // }
    // target = pos.page;
    // }
    // return target;
    // }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public String toString() {
        StringBuilder buff = new StringBuilder();
        DataUtils.appendMap(buff, "id", id);
        DataUtils.appendMap(buff, "name", name);
        String type = getType();
        if (type != null) {
            DataUtils.appendMap(buff, "type", type);
        }
        return buff.toString();
    }

    public void printPage() {
        printPage(true);
    }

    public void printPage(boolean readOffLinePage) {
        System.out.println(root.getPrettyPageInfo(readOffLinePage));
    }

    /**
     * A builder for this class.
     */
    public static class Builder<K, V> extends StorageMapBuilder<BTreeMap<K, V>, K, V> {
        @Override
        public BTreeMap<K, V> openMap() {
            return new BTreeMap<>(name, keyType, valueType, config);
        }
    }

    @Override
    public void remove() {
        store.remove();
    }

    public long commit() {
        return store.commit();
    }

    public void rollback() {
        store.rollback();
    }

    public void rollbackTo(long version) {
        store.rollbackTo(version);
    }

    void writeInBackground(int autoCommitDelay) {
        store.writeInBackground(autoCommitDelay);
    }

    @Override
    public void save() {
        commit();
    }
}