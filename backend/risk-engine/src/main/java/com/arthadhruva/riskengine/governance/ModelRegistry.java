package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.score.ModelService;
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
 * <p><b>Honest tiers.</b> Only the two models that carry a card with out-of-time validation are listed
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

    public ModelRegistry(ModelService pd, SurvivalModel survival, MeterRegistry meters) throws IOException {
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

        entries.add(unvalidated("lgd", "Loss given default", "Fitted LGD by origination features; floor of the collateral-based "
                + "LGD in the ECL engine", 1, "lgd_model.json", "Beta regression (logit link)",
                List.of("No model card or out-of-time validation", "Fitted on 2017-2025 defaults only, a period of rising house prices",
                        "Uses the raw note rate, which moves with the rate cycle")));
        entries.add(unvalidated("regime_hmm", "Macro regime", "Calm / stressed regime and its transition matrix, a driver of the "
                + "survival model and of the regime forecast", 1, "hmm_regime.json", "Two-state hidden Markov model",
                List.of("No model card", "Estimated from 99 months containing a single stress episode")));
        entries.add(unvalidated("early_warning", "Early warning", "Flags performing loans drifting toward delinquency from "
                + "their payment history", 2, "early_warning_model.onnx", "LightGBM with isotonic calibration",
                List.of("No model card or out-of-time validation in this release")));
        entries.add(unvalidated("trajectory_lstm", "Delinquency trajectory", "Sequence model over a loan's monthly payment "
                + "history", 2, "lstm_model.onnx", "LSTM", List.of("No model card or out-of-time validation in this release")));
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

    private static Entry unvalidated(String id, String name, String purpose, int tier, String artifact, String algorithm,
                                     List<String> gaps) throws IOException {
        return new Entry(id, name, purpose, tier, false, "unversioned", artifact, sha256(read(artifact)), null, null,
                algorithm, Map.of(), gaps);
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
