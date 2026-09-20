package com.arthadhruva.riskengine.insights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Topics over short free-text notes: TF-IDF vectors (L2-normalised, so Euclidean k-means on them
 * behaves like cosine clustering), then k-means. Each topic reports its highest-weight terms and
 * the notes closest to its centre. Vocabulary is capped to the most informative terms so the vectors
 * stay small; everything is O(notes * vocabulary * k * iterations).
 */
public final class TopicModel {

    public record Topic(List<String> terms, List<String> examples, int size) {
    }

    private static final Pattern TOKEN = Pattern.compile("[a-z]{3,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had",
            "her", "was", "one", "our", "out", "has", "have", "his", "how", "its", "may", "new", "now", "see", "she", "too",
            "use", "who", "did", "get", "that", "this", "with", "from", "they", "will", "your", "been", "were", "what",
            "when", "which", "their", "there", "about", "would", "these", "those", "than", "then", "into", "also", "loan");
    private static final int MAX_VOCAB = 400;

    private TopicModel() {
    }

    public static List<Topic> topics(List<String> notes, int k, long seed) {
        if (notes.size() < 2) {
            return List.of();
        }
        List<Map<String, Integer>> docs = new ArrayList<>();
        Map<String, Integer> docFreq = new HashMap<>();
        for (String note : notes) {
            Map<String, Integer> tf = new HashMap<>();
            var matcher = TOKEN.matcher(note.toLowerCase());
            while (matcher.find()) {
                String word = matcher.group();
                if (!STOP.contains(word)) {
                    tf.merge(word, 1, Integer::sum);
                }
            }
            docs.add(tf);
            for (String word : tf.keySet()) {
                docFreq.merge(word, 1, Integer::sum);
            }
        }
        int n = notes.size();
        List<String> vocab = docFreq.entrySet().stream()
                .filter(e -> e.getValue() >= 2 && e.getValue() < n) // drop one-offs and words in every note
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_VOCAB).map(Map.Entry::getKey).toList();
        if (vocab.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < vocab.size(); i++) {
            index.put(vocab.get(i), i);
        }

        double[][] vectors = new double[n][vocab.size()];
        for (int i = 0; i < n; i++) {
            double norm = 0;
            for (var e : docs.get(i).entrySet()) {
                Integer col = index.get(e.getKey());
                if (col != null) {
                    double w = (1 + Math.log(e.getValue())) * Math.log((double) n / docFreq.get(e.getKey()));
                    vectors[i][col] = w;
                    norm += w * w;
                }
            }
            if (norm > 0) {
                norm = Math.sqrt(norm);
                for (int j = 0; j < vectors[i].length; j++) {
                    vectors[i][j] /= norm;
                }
            }
        }

        KMeans.Result result = KMeans.cluster(vectors, Math.min(k, n), seed, 40);
        List<Topic> topics = new ArrayList<>();
        for (int c = 0; c < result.centroids().length; c++) {
            final int cluster = c;
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (result.assignment()[i] == cluster) {
                    members.add(i);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            double[] centroid = result.centroids()[c];
            List<String> terms = new ArrayList<>();
            java.util.stream.IntStream.range(0, vocab.size()).boxed()
                    .sorted(Comparator.comparingDouble((Integer j) -> centroid[j]).reversed()).limit(5)
                    .filter(j -> centroid[j] > 0).forEach(j -> terms.add(vocab.get(j)));
            List<String> examples = members.stream()
                    .sorted(Comparator.comparingDouble(i -> KMeans.squaredDistance(vectors[i], centroid)))
                    .limit(3).map(notes::get).toList();
            topics.add(new Topic(terms, examples, members.size()));
        }
        topics.sort(Comparator.comparingInt(Topic::size).reversed());
        return topics;
    }
}
