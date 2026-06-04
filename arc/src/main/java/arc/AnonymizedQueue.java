package arc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

final class AnonymizedQueue<T> {
    private final Object[] data;
    private final Map<T, Integer> pos;              // optional reverse-lookup (by equals/hashCode)
    private final ArrayList<Integer> free;          // indices currently unused
    private final ArrayList<Integer> occupied;      // indices currently used
    private final Random rng;

    public AnonymizedQueue(int cap, Random rng) {
        if (cap <= 0) throw new IllegalArgumentException("cap must be > 0");
        this.data = new Object[cap];
        this.pos = new HashMap<>();
        this.free = new ArrayList<>(cap);
        this.occupied = new ArrayList<>(cap);
        this.rng = Objects.requireNonNull(rng, "rng");

        for (int i = 0; i < cap; i++) free.add(i);
    }

    public int capacity() { return data.length; }
    public int size() { return occupied.size(); }
    public boolean isEmpty() { return occupied.isEmpty(); }
    public boolean isFull() { return occupied.size() == data.length; }

    /**
     * Enqueue an item. If an item is evicted due to capacity, return it; else return null.
     */
    @SuppressWarnings("unchecked")
    public T enqueue(T item) {
        Objects.requireNonNull(item, "item");

        // Choose a slot: free slot if available, otherwise evict a random occupied slot.
        final int slot;
        T evicted = null;

        if (!free.isEmpty()) {
            slot = removeRandomIndexFromList(free);
        } else {
            int evictSlot = removeRandomIndexFromList(occupied);
            evicted = (T) data[evictSlot];
            if (evicted != null) pos.remove(evicted);
            data[evictSlot] = null;
            free.add(evictSlot);
            // now take a free slot (could just reuse evictSlot, but keep the logic uniform)
            slot = removeRandomIndexFromList(free);
        }

        // Place item
        data[slot] = item;
        pos.put(item, slot);
        occupied.add(slot);
        return evicted;
    }

    @SuppressWarnings("unchecked")
    public T dequeue() {
        if (occupied.isEmpty()) throw new NoSuchElementException("Empty");

        int slot = removeRandomIndexFromList(occupied);
        T out = (T) data[slot];
        data[slot] = null;
        if (out != null) pos.remove(out);
        free.add(slot);
        return out;
    }

    public boolean contains(T item) {
        return pos.containsKey(item);
    }

    /**
     * Snapshot of current live elements (non-null). Order is arbitrary.
     */
    @SuppressWarnings("unchecked")
    public List<T> viewLive() {
        ArrayList<T> out = new ArrayList<>(occupied.size());
        for (int slot : occupied) {
            T v = (T) data[slot];
            if (v != null) out.add(v);
        }
        return out;
    }

    private int removeRandomIndexFromList(ArrayList<Integer> list) {
        int k = rng.nextInt(list.size());
        int val = list.get(k);
        int lastIdx = list.size() - 1;
        // swap-remove
        list.set(k, list.get(lastIdx));
        list.remove(lastIdx);
        return val;
    }
}
