package com.arthadhruva.riskengine.borrower;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** The organization's default contact for borrowers (V30), used when a loan has no open case with an assigned
 * analyst. Callers run under the tenant context (row-level security). */
@Service
public class BorrowerContactService {

    /** @param email null when the contact is reached some other way than email */
    public record Contact(String name, String title, String email) {
    }

    private final JdbcTemplate jdbc;

    public BorrowerContactService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Contact> find(Long tenantId) {
        List<Contact> rows = jdbc.query("SELECT name, title, email FROM borrower_contact WHERE tenant_id = ?",
                (rs, i) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3)), tenantId);
        return rows.stream().findFirst();
    }

    public void save(Long tenantId, Contact contact) {
        jdbc.update("INSERT INTO borrower_contact (tenant_id, name, title, email) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET name = EXCLUDED.name, title = EXCLUDED.title, "
                        + "email = EXCLUDED.email, updated_at = now()",
                tenantId, contact.name(), contact.title(), contact.email());
    }

    public boolean clear(Long tenantId) {
        return jdbc.update("DELETE FROM borrower_contact WHERE tenant_id = ?", tenantId) > 0;
    }
}
