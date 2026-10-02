package com.arthadhruva.riskengine.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "access_request")
public class AccessRequest {

    public enum Status { PENDING, APPROVED, DECLINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "work_email", nullable = false)
    private String workEmail;

    @Column(name = "job_title")
    private String jobTitle;

    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "organization_id")
    private Long organizationId;

    protected AccessRequest() {
        // required by JPA
    }

    public AccessRequest(String companyName, String contactName, String workEmail, String jobTitle, String message) {
        this.companyName = companyName;
        this.contactName = contactName;
        this.workEmail = workEmail;
        this.jobTitle = jobTitle;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public void approve(String reviewer, Long organizationId) {
        this.status = Status.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.organizationId = organizationId;
    }

    public void decline(String reviewer) {
        this.status = Status.DECLINED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getContactName() { return contactName; }
    public String getWorkEmail() { return workEmail; }
    public String getJobTitle() { return jobTitle; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Long getOrganizationId() { return organizationId; }
}
