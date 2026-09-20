package com.arthadhruva.riskengine.insights;

import java.util.Random;

/**
 * Lloyd's k-means with k-means++ seeding, deterministic for a given seed (so results are
 * reproducible and testable). Time O(iterations * n * k * d); memory O(n + k*d). Empty clusters
 * are re-seeded from the point farthest from its centroid rather than left dead.
 */
public final class KMeans {

    public record Result(int[] assignment, double[][] centroids, int iterations) {
    }

    private KMeans() {
    }

    public static Result cluster(double[][] points, int k, long seed, int maxIterations) {
        int n = points.length;
        if (n == 0 || k <= 0) {
            throw new IllegalArgumentException("need at least one point and k >= 1");
        }
        k = Math.min(k, n);
        int d = points[0].length;
        Random random = new Random(seed);
        double[][] centroids = seed(points, k, random);
        int[] assignment = new int[n];
        java.util.Arrays.fill(assignment, -1);

        int iter = 0;
        for (; iter < maxIterations; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = nearest(points[i], centroids);
                if (best != assignment[i]) {
                    assignment[i] = best;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            double[][] sums = new double[k][d];
            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                counts[assignment[i]]++;
                for (int j = 0; j < d; j++) {
                    sums[assignment[i]][j] += points[i][j];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) {
                    centroids[c] = points[farthestPoint(points, assignment, centroids)].clone();
                } else {
                    for (int j = 0; j < d; j++) {
                        centroids[c][j] = sums[c][j] / counts[c];
                    }
                }
            }
        }
        return new Result(assignment, centroids, iter);
    }

    private static double[][] seed(double[][] points, int k, Random random) {
        double[][] centroids = new double[k][];
        centroids[0] = points[random.nextInt(points.length)].clone();
        double[] dist = new double[points.length];
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (int i = 0; i < points.length; i++) {
                dist[i] = squaredDistance(points[i], centroids[nearest(points[i], java.util.Arrays.copyOf(centroids, c))]);
                total += dist[i];
            }
            double pick = random.nextDouble() * total;
            int chosen = points.length - 1;
            for (int i = 0; i < points.length; i++) {
                pick -= dist[i];
                if (pick <= 0) {
                    chosen = i;
                    break;
                }
            }
            centroids[c] = points[chosen].clone();
        }
        return centroids;
    }

    static int nearest(double[] p, double[][] centroids) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            double dist = squaredDistance(p, centroids[c]);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static int farthestPoint(double[][] points, int[] assignment, double[][] centroids) {
        int far = 0;
        double farDist = -1;
        for (int i = 0; i < points.length; i++) {
            double dist = squaredDistance(points[i], centroids[assignment[i]]);
            if (dist > farDist) {
                farDist = dist;
                far = i;
            }
        }
        return far;
    }

    static double squaredDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
