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
    private final Map<String, List<String>> loanIdsByState;

    private final TenantLoanService tenantLoans;

    public LoanCatalogService(TenantLoanService tenantLoans) throws IOException {
        this.tenantLoans = tenantLoans;
        ObjectMapper mapper = new ObjectMapper();
        List<LoanFeatures> loans;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("loan_catalog.json")) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: loan_catalog.json");
            }
            loans = mapper.readValue(is.readAllBytes(), new TypeReference<List<LoanFeatures>>() {});
        }
        this.loansById = loans.stream().collect(Collectors.toMap(LoanFeatures::loanId, l -> l));
        // Built once: state -> loan ids is an O(1) lookup per request instead of an O(n) scan of the catalog.
        this.loanIdsByState = loans.stream().collect(Collectors.groupingBy(
                l -> l.propertyState().toUpperCase(), Collectors.mapping(LoanFeatures::loanId, Collectors.toUnmodifiableList())));
    }

    /** The tenant's own uploaded portfolio if it has one, otherwise the shared demo catalog. */
    public List<LoanFeatures> all() {
        Long tenant = ownPortfolioTenant();
        return tenant != null ? tenantLoans.all(tenant) : List.copyOf(loansById.values());
    }

    private Long ownPortfolioTenant() {
        return com.arthadhruva.riskengine.tenant.TenantContext.getOptional()
                .filter(tenantLoans::hasPortfolio).orElse(null);
    }

    /** The shared demo catalog regardless of tenant (used as the population baseline for attribution). */
    public List<LoanFeatures> demoLoans() {
        return List.copyOf(loansById.values());
    }

    public List<String> loanIdsInState(String state) {
        Long tenant = ownPortfolioTenant();
        if (tenant != null) {
            return tenantLoans.loanIdsInState(tenant, state);
        }
        return loanIdsByState.getOrDefault(state.toUpperCase(), List.of());
    }

    public LoanFeatures find(String loanId) {
        Long tenant = ownPortfolioTenant();
        if (tenant != null) {
            return tenantLoans.find(tenant, loanId).orElse(null);
        }
        return loansById.get(loanId);
    }
}
