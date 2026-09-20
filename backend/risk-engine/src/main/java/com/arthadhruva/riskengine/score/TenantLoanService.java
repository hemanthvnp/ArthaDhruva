package com.arthadhruva.riskengine.score;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A tenant's own loan portfolio: uploaded by an admin, or pushed by a core system through the
 * ingest API. Every record is validated with the same constraints as the scoring API and
 * reported individually, so one bad row never rejects the batch. Row-level security scopes every
 * query to the request's tenant.
 */
@Service
public class TenantLoanService {

    public static final int MAX_BATCH = 500;

    public record ItemResult(int index, String loanId, boolean ok, String error) {
    }

    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    public TenantLoanService(JdbcTemplate jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public List<ItemResult> upsertAll(Long tenantId, List<LoanFeatures> loans) {
        if (loans.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " loans per request");
        }
        List<ItemResult> results = new ArrayList<>();
        for (int i = 0; i < loans.size(); i++) {
            LoanFeatures loan = loans.get(i);
            String problem = validate(loan);
            if (problem != null) {
                results.add(new ItemResult(i, loan == null ? null : loan.loanId(), false, problem));
                continue;
            }
            try {
                jdbc.update("INSERT INTO tenant_loan (tenant_id, loan_id, property_state, features) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (tenant_id, loan_id) DO UPDATE SET property_state = EXCLUDED.property_state, "
                                + "features = EXCLUDED.features, updated_at = now()",
                        tenantId, loan.loanId(), loan.propertyState().toUpperCase(), mapper.writeValueAsString(loan));
                results.add(new ItemResult(i, loan.loanId(), true, null));
            } catch (Exception e) {
                results.add(new ItemResult(i, loan.loanId(), false, "Could not store record"));
            }
        }
        return results;
    }

    private String validate(LoanFeatures loan) {
        if (loan == null) {
            return "Empty record";
        }
        if (loan.loanId() == null || loan.loanId().isBlank() || loan.loanId().length() > 80) {
            return "loanId is required (max 80 characters)";
        }
        var violations = validator.validate(loan);
        return violations.isEmpty() ? null : violations.stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).sorted()
                .collect(Collectors.joining(", ", "Invalid: ", ""));
    }

    public boolean hasPortfolio(Long tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tenant_loan WHERE tenant_id = ?)", Boolean.class, tenantId));
    }

    public List<LoanFeatures> all(Long tenantId) {
        return jdbc.query("SELECT features FROM tenant_loan WHERE tenant_id = ? ORDER BY loan_id",
                (rs, i) -> mapper.readValue(rs.getString(1), LoanFeatures.class), tenantId);
    }

    public Optional<LoanFeatures> find(Long tenantId, String loanId) {
        return jdbc.query("SELECT features FROM tenant_loan WHERE tenant_id = ? AND loan_id = ?",
                (rs, i) -> mapper.readValue(rs.getString(1), LoanFeatures.class), tenantId, loanId).stream().findFirst();
    }

    public List<String> loanIdsInState(Long tenantId, String state) {
        return jdbc.queryForList("SELECT loan_id FROM tenant_loan WHERE tenant_id = ? AND property_state = ?",
                String.class, tenantId, state.toUpperCase());
    }

    /** Reverts the tenant to the shared demo catalog. */
    public int clear(Long tenantId) {
        return jdbc.update("DELETE FROM tenant_loan WHERE tenant_id = ?", tenantId);
    }
}
