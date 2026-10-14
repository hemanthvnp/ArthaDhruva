package com.arthadhruva.riskengine.roughset;

/**
 * Counter-based random numbers: every value is a pure function of a seed and an index, with no shared
 * generator state, so a granule member is the same on any thread count and in any evaluation order (the
 * same property {@code CvarEngine} gets by replaying scenario {@code s} from its own seed). The
 * independent Python reference in {@code backend/golden_rough_set.py} implements the same recurrence.
 */
final class SplitMix64 {

    static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    private SplitMix64(long state) {
        this.state = state;
    }

    /** The stream of member {@code index}: its start is itself a mixed value, so streams do not overlap. */
    static SplitMix64 forMember(long seed, int index) {
        return new SplitMix64(mix(seed + (index + 1L) * GAMMA));
    }

    static long mix(long x) {
        long z = x;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    long nextLong() {
        state += GAMMA;
        return mix(state);
    }

    /** Uniform on [0, 1) with 53 bits. */
    double nextUnit() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** 64-bit FNV-1a over the UTF-8 bytes, for a stable seed from a canonical request string. */
    static long fnv1a(String text) {
        long hash = 0xCBF29CE484222325L;
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
