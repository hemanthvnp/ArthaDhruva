package com.arthadhruva.riskengine.insights;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsAlgorithmsTest {

    @Test
    void kMeansSeparatesTwoObviousBlobsAndIsDeterministic() {
        Random r = new Random(1);
        double[][] pts = new double[100][];
        for (int i = 0; i < 50; i++) pts[i] = new double[]{r.nextGaussian(), r.nextGaussian()};
        for (int i = 50; i < 100; i++) pts[i] = new double[]{10 + r.nextGaussian(), 10 + r.nextGaussian()};

        KMeans.Result a = KMeans.cluster(pts, 2, 7, 50);
        KMeans.Result b = KMeans.cluster(pts, 2, 7, 50);
        assertArrayEquals(a.assignment(), b.assignment(), "same seed, same result");
        for (int i = 1; i < 50; i++) assertEquals(a.assignment()[0], a.assignment()[i]);
        for (int i = 51; i < 100; i++) assertEquals(a.assignment()[50], a.assignment()[i]);
        assertTrue(a.assignment()[0] != a.assignment()[50]);
    }

    @Test
    void topicsRecoverTheTwoThemesInTheNotes() {
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            notes.add("Borrower missed payment again, delinquency escalated to collections " + i);
            notes.add("Verified income documents and employment letter received from borrower " + i);
        }
        List<TopicModel.Topic> topics = TopicModel.topics(notes, 2, 3);
        assertEquals(2, topics.size());
        String all0 = String.join(" ", topics.get(0).terms());
        String all1 = String.join(" ", topics.get(1).terms());
        boolean delinquencyTopic = all0.contains("delinquency") || all1.contains("delinquency") || all0.contains("payment") || all1.contains("payment");
        boolean incomeTopic = all0.contains("income") || all1.contains("income") || all0.contains("documents") || all1.contains("documents");
        assertTrue(delinquencyTopic && incomeTopic, "topics: " + topics);
        assertEquals(24, topics.get(0).size() + topics.get(1).size());
    }

    private static LoanFeatures loan(int credit, double ltv, double upb) {
        return new LoanFeatures("x", credit, 30.0, upb, ltv, ltv, 6.0, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", "CA");
    }

    @Test
    void segmentsSplitPrimeFromSubprimeAndNameTheDifference() {
        List<LoanFeatures> loans = new ArrayList<>();
        Random r = new Random(5);
        for (int i = 0; i < 40; i++) loans.add(loan(780 + r.nextInt(30), 50 + r.nextInt(10), 400000 + r.nextInt(50000)));
        for (int i = 0; i < 40; i++) loans.add(loan(560 + r.nextInt(30), 92 + r.nextInt(6), 120000 + r.nextInt(30000)));

        var segments = BorrowerSegmenter.segment(loans, 2, 11);
        assertEquals(2, segments.size());
        assertEquals(80, segments.get(0).size() + segments.get(1).size());
        var lowCredit = segments.stream().filter(s -> s.averages().get("creditScore") < 650).findFirst().orElseThrow();
        assertTrue(lowCredit.definingTraits().stream().anyMatch(t -> t.feature().equals("creditScore") && t.direction().equals("low")));
    }
}
