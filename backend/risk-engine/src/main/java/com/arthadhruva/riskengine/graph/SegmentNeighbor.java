package com.arthadhruva.riskengine.graph;

/** One state reachable within a hop cutoff, and its shortest-path distance in hops. */
public record SegmentNeighbor(String state, int hops) {
}
