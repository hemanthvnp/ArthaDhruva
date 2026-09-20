package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/** One free-text note an analyst left on a loan -- an append-only thread, never edited or
 * deleted, same "immutable record" spirit as the model-invocation audit trail. See {@code
 * security.User}'s class doc for why {@code tenantFilter} is a backstop, not the primary guard. */
@Entity
@Table(name = "loan_note")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoanNote implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "loan_id", nullable = false)
    private String loanId;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false, length = 2000)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanNote() {
        // required by JPA
    }

    public LoanNote(Long tenantId, String loanId, String author, String text) {
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.author = author;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    public String getLoanId() {
        return loanId;
    }

    public String getAuthor() {
        return author;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
