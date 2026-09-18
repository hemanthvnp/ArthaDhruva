package com.arthadhruva.riskengine.score;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browsable catalog of real loans (exported by backend/export_loan_catalog.py from the actual
 * processed dataset) so an analyst can look up and score a real loan by its actual
 * loan_sequence_number instead of hand-typing 16 feature values into a form every time. Loaded
 * once at startup -- 400 loans is small enough to keep entirely in memory, same reasoning as the
 * committed model artifacts.
 */
@Service
public class LoanCatalogService {

    private final Map<String, LoanFeatures> loansById;

    public LoanCatalogService() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<LoanFeatures> loans;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("loan_catalog.json")) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: loan_catalog.json");
            }
            loans = mapper.readValue(is.readAllBytes(), new TypeReference<List<LoanFeatures>>() {});
        }
        this.loansById = loans.stream().collect(Collectors.toMap(LoanFeatures::loanId, l -> l));
    }

    public List<LoanFeatures> all() {
        return List.copyOf(loansById.values());
    }

    public LoanFeatures find(String loanId) {
        return loansById.get(loanId);
    }
}
