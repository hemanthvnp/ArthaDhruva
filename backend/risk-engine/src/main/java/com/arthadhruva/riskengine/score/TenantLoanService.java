package com.arthadhruva.riskengine.score;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A tenant's own loan portfolio: uploaded by an admin, or pushed by a core system through the ingest
 * API. Every record is checked twice and reported individually, so one bad row never rejects the batch:
 * the same structural constraints as the scoring API, then the models' own domain
 * ({@link LoanInputValidator}: known category codes, possible values). A loan the models cannot score is
 * refused at the door instead of being stored and silently dropped from every later portfolio run.
 * Row-level security scopes every query to the request's tenant.
 */
@Service
public class TenantLoanService {

    private static final Logger log = LoggerFactory.getLogger(TenantLoanService.class);

    public static final int MAX_BATCH = 500;
    public static final int MAX_PAGE = 2000;

    public record ItemResult(int index, String loanId, boolean ok, String error) {
    }

    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final LoanInputValidator domain;
    private final ObjectMapper mapper = new ObjectMapper();

    public TenantLoanService(JdbcTemplate jdbc, Validator validator, LoanInputValidator domain) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.domain = domain;
    }

    public List<ItemResult> upsertAll(Long tenantId, List<LoanFeatures> loans) {
        if (loans.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " loans per request");
        }
        ItemResult[] results = new ItemResult[loans.size()];
        List<Object[]> rows = new ArrayList<>(loans.size());
        List<Integer> rowIndex = new ArrayList<>(loans.size());
        for (int i = 0; i < loans.size(); i++) {
            LoanFeatures raw = loans.get(i);
            String problem = validate(raw);
            if (problem != null) {
                results[i] = new ItemResult(i, raw == null ? null : raw.loanId(), false, problem);
                continue;
            }
            LoanFeatures loan = raw.normalized();
            rows.add(new Object[]{tenantId, loan.loanId(), loan.propertyState(), mapper.writeValueAsString(loan)});
            rowIndex.add(i);
        }
        // One round trip for the whole batch, not one per loan.
        boolean stored = true;
        if (!rows.isEmpty()) {
            try {
                jdbc.batchUpdate("INSERT INTO tenant_loan (tenant_id, loan_id, property_state, features) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, loan_id) DO UPDATE SET property_state = EXCLUDED.property_state, "
                        + "features = EXCLUDED.features, updated_at = now()", rows);
            } catch (DataAccessException e) {
                log.error("Could not store a batch of {} loans for tenant {}", rows.size(), tenantId, e);
                stored = false;
            }
        }
        for (int k = 0; k < rows.size(); k++) {
            int i = rowIndex.get(k);
            results[i] = new ItemResult(i, (String) rows.get(k)[1], stored, stored ? null : "Could not store record");
        }
        return List.of(results);
    }

    private String validate(LoanFeatures loan) {
        if (loan == null) {
            return "Empty record";
        }
        if (loan.loanId() == null || loan.loanId().isBlank() || loan.loanId().length() > 80) {
            return "loanId is required (max 80 characters)";
        }
        var violations = validator.validate(loan);
        if (!violations.isEmpty()) {
            return violations.stream().map(ConstraintViolation::getPropertyPath).map(Object::toString).sorted()
                    .collect(Collectors.joining(", ", "Invalid: ", ""));
        }
        try {
            domain.check(loan.normalized());
            return null;
        } catch (LoanInputValidator.InvalidLoanException e) {
            return e.getFields().entrySet().stream().map(f -> f.getKey() + " " + f.getValue())
                    .collect(Collectors.joining("; ", "Outside the model's domain: ", ""));
        }
    }

    public boolean hasPortfolio(Long tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tenant_loan WHERE tenant_id = ?)", Boolean.class, tenantId));
    }

    public int count(Long tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_loan WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /** Every loan, in loan-id order: for whole-portfolio computations, not for listing to a client. */
    public List<LoanFeatures> all(Long tenantId) {
        return jdbc.query("SELECT features FROM tenant_loan WHERE tenant_id = ? ORDER BY loan_id",
                (rs, i) -> mapper.readValue(rs.getString(1), LoanFeatures.class), tenantId);
    }

    /** One page in loan-id order, optionally narrowed to ids containing {@code idContains} and to a state. */
    public List<LoanFeatures> page(Long tenantId, String idContains, String state, int limit, int offset) {
        return jdbc.query("SELECT features FROM tenant_loan WHERE tenant_id = ? "
                        + "AND (?::text IS NULL OR loan_id ILIKE '%' || ? || '%') AND (?::text IS NULL OR property_state = ?) "
                        + "ORDER BY loan_id LIMIT ? OFFSET ?",
                (rs, i) -> mapper.readValue(rs.getString(1), LoanFeatures.class),
                tenantId, idContains, escapeLike(idContains), state, state, limit, offset);
    }

    public int count(Long tenantId, String idContains, String state) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_loan WHERE tenant_id = ? "
                        + "AND (?::text IS NULL OR loan_id ILIKE '%' || ? || '%') AND (?::text IS NULL OR property_state = ?)",
                Integer.class, tenantId, idContains, escapeLike(idContains), state, state);
        return count == null ? 0 : count;
    }

    /** % and _ in a search term are text to find, not wildcards. */
    private static String escapeLike(String term) {
        return term == null ? null : term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
