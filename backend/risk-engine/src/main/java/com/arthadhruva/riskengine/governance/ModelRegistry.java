package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.graph.SegmentGraphArtifact;
import com.arthadhruva.riskengine.score.ModelService;
import com.arthadhruva.riskengine.survival.DelinquencyModel;
import com.arthadhruva.riskengine.survival.LgdPredictor;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The model inventory: every model this service runs, the exact artifact behind it, and how far it has
 * been validated.
 *
 * <p><b>Integrity.</b> A model card that declares a checksum is binding: at startup the bytes on the
 * classpath are hashed and compared, and a mismatch stops the application. A card describing one model
 * while another one answers requests is the failure this exists to make impossible.
 *
 * <p><b>Honest tiers.</b> Only the models that carry a card with out-of-time validation are listed
 * as validated. The others are in the inventory with their checksum and a plain statement of what is
 * missing, because an inventory that only lists the well-documented models is not an inventory.
 *
 * <p>Each model is also exported as an info metric ({@code model_info{model, version, sha256} 1}), so a
 * dashboard shows which version every replica is serving during a rollout.
 */
@Component
public class ModelRegistry {

    /**
     * @param tier             1 = feeds expected credit loss and capital figures; 2 = analyst decision support
     * @param validated        has a model card with out-of-time validation
     * @param checksumVerified the artifact matched the checksum its card declares (null: no card to match)
     * @param headline         a few key validation numbers, for the inventory table
     * @param gaps             what is missing before this model could be called validated
     */
    public record Entry(String id, String name, String purpose, int tier, boolean validated, String version,
                        String artifact, String sha256, Boolean checksumVerified, String trainedAt, String algorithm,
                        Map<String, Object> headline, List<String> gaps) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, JsonNode> cards = new LinkedHashMap<>();
    private final JsonNode survivalBacktest;

    public ModelRegistry(ModelService pd, SurvivalModel survival, DelinquencyModel delinquency, SegmentGraphArtifact segmentGraph,
                         MeterRegistry meters) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode pdCard = mapper.readTree(read("pd_model_card.json"));
        String pdSha = sha256(read("model.onnx"));
        if (!pdSha.equals(pd.declaredArtifactSha256())) {
            throw new IllegalStateException("model.onnx does not match the checksum in pd_model_card.json");
        }
        JsonNode oot = pdCard.get("validation").get("out_of_time");
        Map<String, Object> pdHeadline = new LinkedHashMap<>();
        pdHeadline.put("outOfTimeAuc", oot.get("auc").asDouble());
        pdHeadline.put("outOfTimeKs", oot.get("ks").asDouble());
        pdHeadline.put("outOfTimeVintages", oot.get("test_vintages").asString());
        pdHeadline.put("loans", pdCard.get("n_eligible_loans").asLong());
        entries.add(new Entry("pd_24m", "Origination PD", "Probability of default within 24 months of origination; "
                + "explanations and adverse-action reason codes", 1, true, pdCard.get("version").asString(), "model.onnx", pdSha,
                true, pdCard.get("trained_at").asString(), pdCard.get("algorithm").asString(), pdHeadline, List.of()));
        cards.put("pd_24m", pdCard);

        // SurvivalModel has already refused to load an artifact that does not match its card.
        JsonNode survivalCard = survival.metadata();
        JsonNode survivalOot = survivalCard.get("validation").get("out_of_time");
        Map<String, Object> survivalHeadline = new LinkedHashMap<>();
        survivalHeadline.put("outOfTimeDefaultAuc", survivalOot.get("default_auc").asDouble());
        survivalHeadline.put("outOfTimePrepayAuc", survivalOot.get("prepay_auc").asDouble());
        survivalHeadline.put("outOfTimeLogLoss", survivalOot.get("weighted_log_loss").asDouble());
        survivalHeadline.put("ageOnlyBaselineLogLoss", survivalOot.get("age_only_baseline_log_loss").asDouble());
        survivalHeadline.put("loanMonths", survivalCard.get("training").get("rows").asLong());
        entries.add(new Entry("survival", "Competing-risks survival", "Monthly default and prepayment hazards behind PD term "
                + "structures, lifetime ECL (IFRS 9 / CECL) and scenario analysis", 1, true,
                survivalCard.get("version").asString(), "survival_model.onnx", survival.artifactSha256(), true,
                survivalCard.get("trained_at").asString(), survivalCard.get("algorithm").asString(), survivalHeadline, List.of()));
        cards.put("survival", survivalCard);
        this.survivalBacktest = mapper.readTree(read("survival_backtest.json"));

        // DelinquencyModel has already refused to load an artifact that does not match its card.
        JsonNode delinquencyCard = delinquency.card();
        JsonNode held = delinquencyCard.get("validation").get("production_fit_on_held_out_loans").get("one_step");
        Map<String, Object> delinquencyHeadline = new LinkedHashMap<>();
        delinquencyHeadline.put("delinquentLogLoss", held.get("delinquency model").get("delinquent_rows").asDouble());
        delinquencyHeadline.put("survivalModelDelinquentLogLoss", held.get("survival model").get("delinquent_rows").asDouble());
        delinquencyHeadline.put("rollRateDelinquentLogLoss", held.get("roll-rate table").get("delinquent_rows").asDouble());
        delinquencyHeadline.put("failedGates", delinquencyCard.get("validation").get("gates").get("failures_at_export").size());
        delinquencyHeadline.put("loanMonths", delinquencyCard.get("training").get("rows").asLong());
        List<String> delinquencyGaps = new ArrayList<>();
        delinquencyCard.get("limitations").forEach(l -> {
            String text = l.asString();
            if (text.contains("no housing downturn") || text.contains("Sparse deep-delinquency") || text.contains("forbearance")) {
                delinquencyGaps.add(text);
            }
        });
        entries.add(new Entry(DelinquencyModel.ID, "Delinquency transitions", "Month-to-month moves between current, 30 and 60 days "
                + "past due, default and prepaid; projections of loans whose payment status and history are known, with ranges",
                1, true, delinquencyCard.get("version").asString(), "delinquency_model.onnx", delinquency.artifactSha256(), true,
                delinquencyCard.get("trained_at").asString(), delinquencyCard.get("algorithm").asString(), delinquencyHeadline,
                delinquencyGaps));
        cards.put(DelinquencyModel.ID, delinquencyCard);

        addLgd();
        addRegime(mapper);
        addEarlyWarning(mapper);
        if (!segmentGraph.sha256().equals(segmentGraph.declaredSha256())) {
            throw new IllegalStateException(SegmentGraphArtifact.FILE + " does not match the checksum in "
                    + SegmentGraphArtifact.CARD);
        }
        JsonNode graphCard = segmentGraph.card();
        Map<String, Object> graphHeadline = new LinkedHashMap<>(segmentGraph.validationHeadline());
        graphHeadline.remove("leadsNote");  // a sentence, and the same text is in the limitations below
        graphHeadline.values().removeIf(java.util.Objects::isNull);
        // A bootstrap p of 0 means no resample at or below zero: an upper bound, not zero.
        java.util.regex.Matcher leading = java.util.regex.Pattern.compile("^(\\d+) ")
                .matcher(graphCard.get("validation").get("protocol").get("versus").asString());
        int draws = leading.find() ? Integer.parseInt(leading.group(1)) : 0;
        if (draws > 0 && Double.valueOf(0.0).equals(graphHeadline.get("pAgainstSameDivision"))) {
            graphHeadline.put("pAgainstSameDivision", "< " + java.math.BigDecimal.valueOf(1.0 / draws).stripTrailingZeros().toPlainString());
        }
        graphHeadline.put("links", segmentGraph.graph().movesWith().size());
        graphHeadline.put("regions", segmentGraph.graph().regions().size());
        // What this release does not have, not the general limitations (the card lists those).
        List<String> graphGaps = new ArrayList<>();
        graphCard.get("limitations").forEach(l -> {
            String text = l.asString();
            if (text.contains("omitted") || text.contains("absent")) {
                graphGaps.add(text);
            }
        });
        entries.add(new Entry("segment_graph", "Segment graph", "Which states' unemployment and house prices move "
                + "together beyond the national cycle; portfolio-level analysis only", 2, true, segmentGraph.version(),
                SegmentGraphArtifact.FILE, segmentGraph.sha256(), true, graphCard.get("trained_at").asString(),
                graphCard.get("algorithm").asString(), graphHeadline, graphGaps));
        cards.put("segment_graph", graphCard);

        Map<String, Object> copula = new LinkedHashMap<>();
        copula.put("defaultAssetCorrelation", CvarRequest.DEFAULT_ASSET_CORRELATION);
        entries.add(new Entry("portfolio_copula", "Portfolio loss distribution", "Value-at-Risk, expected shortfall and tail "
                + "contributions of a portfolio", 1, false, "analytical", null, null, null, null,
                "One-factor Gaussian copula, Monte Carlo with importance sampling", copula,
                List.of("The asset correlation is an input (default: the Basel IRB value for residential mortgages), not estimated from this data",
                        "Checked against the exact finite-portfolio distribution and the ASRF limit in the test suite, not against realized portfolio losses")));

        for (Entry entry : entries) {
            Gauge.builder("model.info", () -> 1)
                    .description("Models served by this instance")
                    .tags("model", entry.id(), "version", entry.version(),
                            "sha256", entry.sha256() == null ? "none" : entry.sha256().substring(0, 12))
                    .register(meters);
        }
    }

    /**
     * A card declares the checksum of the artifact it describes, and the artifact on the classpath must match it: a card for one
     * model while another answers requests is what the registry exists to prevent.
     */
    public static void verifyChecksum(String artifactName, byte[] artifact, String declaredSha256, String cardName) {
        if (!sha256(artifact).equals(declaredSha256)) {
            throw new IllegalStateException(artifactName + " does not match the checksum in " + cardName);
        }
    }

    /**
     * Loss given default is a constant, not a model: the fitted regression that used to stand here was no better than the mean out of
     * time, so the service no longer loads it. The entry says so, with the retired model's results, instead of omitting the floor.
     */
    private void addLgd() {
        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("fixedFloor", LgdPredictor.HISTORICAL_MEAN_LGD);
        headline.put("retiredModelOutOfTimeOverPrediction", 1.9);
        headline.put("retiredModelRankCorrelation", 0.10);
        entries.add(new Entry("lgd", "Loss given default", "A fixed floor under the collateral-based LGD in the ECL engine: the mean "
                + "realized loss on defaulted loans", 1, false, "constant", null, null, null, null,
                "Historical mean of realized loss (2,746 defaulted loans, vintages 2020 to 2024)", headline,
                List.of("A constant, not a fitted model: a Beta regression on origination features was no better than it out of time "
                        + "(it over-predicted 1.9 times, rank correlation 0.10), so it was retired; see MODELS.md",
                        "The data has no housing downturn and about 88% of these defaulted loans lost nothing; severity in a falling-price "
                        + "scenario comes from the collateral shortfall, not from this floor",
                        "Exposure at default is the original balance, a stated simplification")));
    }

    private void addRegime(ObjectMapper mapper) throws IOException {
        JsonNode card = mapper.readTree(read("hmm_regime_card.json"));
        byte[] artifact = read("hmm_regime.json");
        verifyChecksum("hmm_regime.json", artifact, card.get("artifact_sha256").asString(), "hmm_regime_card.json");
        JsonNode shipped = card.get("shipped_chain");
        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("months", card.get("training").get("months").asLong());
        headline.put("refitReproducesShippedChain", card.get("reproduction").get("refit_reproduces_shipped_matrix").asBoolean());
        headline.put("expectedStressSpellMonths", shipped.get("expected_stress_spell_months").asDouble());
        headline.put("longRunStressedShare", shipped.get("long_run_stressed_share").asDouble());
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, JsonNode> cut : card.get("validation").get("out_of_time").properties()) {
            JsonNode r = cut.getValue();
            double toCalm = r.get("chain").get("transition_matrix").get(1).get(0).asDouble();
            int heldOut = r.get("held_out_months").asInt();
            int labelled = r.get("stressed_months_labelled_held_out").asInt();
            int reference = r.get("stressed_months_in_full_fit_held_out").asInt();
            double agreement = r.get("agreement_with_full_fit_on_held_out_months").asDouble();
            if (toCalm < 1e-6) {
                gaps.add(String.format("Refit on the history through %s the stressed state is absorbing (stress-to-calm probability %.0e): the chain "
                        + "is not stable out of time", cut.getKey(), toCalm));
            }
            if (agreement < 0.9) {
                gaps.add(String.format("Refit on the history through %s it labels %d of the %d later months stressed where the full fit labels %d "
                        + "(agreement %.0f%%)", cut.getKey(), labelled, heldOut, reference, 100 * agreement));
            }
            if (r.get("held_out_log_likelihood").get("chain_minus_single_per_month").asDouble() < 0) {
                gaps.add(String.format("Through %s the chain explains the later months worse than a single regime (%.1f log-likelihood per month)",
                        cut.getKey(), r.get("held_out_log_likelihood").get("chain_minus_single_per_month").asDouble()));
            }
        }
        card.get("limitations").forEach(l -> gaps.add(l.asString()));
        entries.add(new Entry("regime_hmm", "Macro regime", "Calm / stressed regime and its transition matrix, a driver of the survival model "
                + "and of the regime forecast", 1, true, card.get("version").asString(), "hmm_regime.json", sha256(artifact), true,
                card.get("validated_at").asString(), card.get("algorithm").asString(), headline, gaps));
        cards.put("regime_hmm", card);
    }

    private void addEarlyWarning(ObjectMapper mapper) throws IOException {
        JsonNode card = mapper.readTree(read("early_warning_card.json"));
        byte[] artifact = read("early_warning_model.onnx");
        verifyChecksum("early_warning_model.onnx", artifact, card.get("artifact_sha256").asString(), "early_warning_card.json");
        verifyChecksum("early_warning_calibration.json", read("early_warning_calibration.json"), card.get("calibration_sha256").asString(),
                "early_warning_card.json");
        JsonNode validation = card.get("validation");
        JsonNode natural = validation.get("out_of_time_natural_rate").get("refit_calibrated");
        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("outOfTimeAuc", validation.get("out_of_time_downsampled").get("roc_auc").asDouble());
        headline.put("outOfTimeNaturalRateAuc", natural.get("roc_auc").asDouble());
        headline.put("outOfTimePrAuc", natural.get("pr_auc").asDouble());
        headline.put("baseRate", natural.get("base_rate").asDouble());
        headline.put("predictedOverActual", natural.get("predicted_over_actual").asDouble());
        List<String> gaps = new ArrayList<>();
        double ratio = natural.get("predicted_over_actual").asDouble();
        if (ratio > 1.25 || ratio < 0.8) {
            gaps.add(String.format("Out of time, after calibration, predicted over actual is %.2f", ratio));
        }
        card.get("limitations").forEach(l -> gaps.add(l.asString()));
        entries.add(new Entry("early_warning", "Early warning", "Flags performing loans drifting toward delinquency from their payment history",
                2, true, card.get("version").asString(), "early_warning_model.onnx", sha256(artifact), true,
                card.get("validated_at").asString(), card.get("algorithm").asString(), headline, gaps));
        cards.put("early_warning", card);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The full model card, for the models that have one. */
    public Optional<JsonNode> card(String id) {
        return Optional.ofNullable(cards.get(id));
    }

    public JsonNode survivalBacktest() {
        return survivalBacktest;
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream is = ModelRegistry.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
