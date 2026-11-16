package clre20.zeroSentinel.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 有上限的 LRU 快取（執行緒安全，鎖範圍很小）。
 */
public final class LruCache<K, V> {

    private final Map<K, V> map;
    private volatile int maxSize;

    public LruCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.map = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.maxSize;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    /** 只有已在快取中時才更新（不會把舊資料重新放回） */
    public synchronized void computeIfPresent(K key, Function<V, V> updater) {
        V v = map.get(key);
        if (v != null) {
            map.put(key, updater.apply(v));
        }
    }

    public synchronized boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public synchronized void remove(K key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }
}
