package com.arthadhruva.riskengine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One free-text note an analyst left on a loan -- an append-only thread, never edited or
 * deleted, same "immutable record" spirit as the model-invocation audit trail. */
@Entity
@Table(name = "loan_note")
public class LoanNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public LoanNote(String loanId, String author, String text) {
        this.loanId = loanId;
        this.author = author;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
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
