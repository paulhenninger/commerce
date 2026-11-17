package com.thirdstone.commerce.simulators;

import java.util.LinkedHashMap;
import java.util.Map;

/** A small map that forgets its oldest entries: the simulators must not grow without bound. */
final class Bounded<K, V> extends LinkedHashMap<K, V> {

    private final int max;

    Bounded(int max) {
        super(16, 0.75f, false);
        this.max = max;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > max;
    }
}
