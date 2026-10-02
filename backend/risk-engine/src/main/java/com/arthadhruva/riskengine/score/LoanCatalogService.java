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

    /** One page of the portfolio and how many loans match in total. */
    public record Page(List<LoanFeatures> loans, int total) {
    }

    private final Map<String, LoanFeatures> loansById;
    private final List<LoanFeatures> demoInOrder;
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
        this.demoInOrder = loans.stream().sorted(java.util.Comparator.comparing(LoanFeatures::loanId)).toList();
        // Built once: state -> loan ids is an O(1) lookup per request instead of an O(n) scan of the catalog.
        this.loanIdsByState = loans.stream().collect(Collectors.groupingBy(
                l -> l.propertyState().toUpperCase(), Collectors.mapping(LoanFeatures::loanId, Collectors.toUnmodifiableList())));
    }

    /** The tenant's own uploaded portfolio if it has one, otherwise the shared demo catalog: every loan,
     * in loan-id order. For whole-portfolio computations; listing to a client goes through {@link #page}. */
    public List<LoanFeatures> all() {
        Long tenant = ownPortfolioTenant();
        return tenant != null ? tenantLoans.all(tenant) : demoInOrder;
    }

    /**
     * A page of the portfolio in loan-id order.
     *
     * @param idContains keep only loans whose id contains this text (case-insensitive); null for all
     * @param state      keep only loans in this state; null for all
     */
    public Page page(String idContains, String state, int limit, int offset) {
        String term = idContains == null || idContains.isBlank() ? null : idContains.trim();
        String wantedState = state == null || state.isBlank() ? null : state.trim().toUpperCase(java.util.Locale.ROOT);
        Long tenant = ownPortfolioTenant();
        if (tenant != null) {
            return new Page(tenantLoans.page(tenant, term, wantedState, limit, offset), tenantLoans.count(tenant, term, wantedState));
        }
        String needle = term == null ? null : term.toLowerCase(java.util.Locale.ROOT);
        List<LoanFeatures> matching = demoInOrder.stream()
                .filter(l -> needle == null || l.loanId().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .filter(l -> wantedState == null || l.propertyState().equalsIgnoreCase(wantedState))
                .toList();
        return new Page(matching.stream().skip(offset).limit(limit).toList(), matching.size());
    }

    private Long ownPortfolioTenant() {
        return com.arthadhruva.riskengine.tenant.TenantContext.getOptional()
                .filter(tenantLoans::hasPortfolio).orElse(null);
    }

    /** The shared demo catalog regardless of tenant (used as the population baseline for attribution). */
    public List<LoanFeatures> demoLoans() {
        return demoInOrder;
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
