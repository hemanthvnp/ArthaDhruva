package com.arthadhruva.riskengine.graph;

import java.util.List;

/**
 * Cache-aside wrapper around {@code List<SegmentNeighbor>} -- {@link
 * com.arthadhruva.riskengine.cache.CacheService#get} deserializes by a single {@code Class<T>}
 * token, so a bare generic list would lose its element type to erasure (Jackson would hand back
 * {@code List<LinkedHashMap>}, not {@code List<SegmentNeighbor>}). Wrapping it in a record with a
 * concrete field type sidesteps that the same way {@code ScoreResponse.CachedScore} does.
 */
public record SegmentNeighborsResponse(List<SegmentNeighbor> neighbors) {
}
