package com.arthadhruva.riskengine.borrower;

import com.arthadhruva.riskengine.score.LoanScoreRecord;
import com.arthadhruva.riskengine.score.LoanScoreService;
import com.arthadhruva.riskengine.score.RiskBand;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.workflow.LoanAttachment;
import com.arthadhruva.riskengine.workflow.LoanAttachmentService;
import com.arthadhruva.riskengine.workflow.LoanCaseService;
import com.arthadhruva.riskengine.workflow.LoanCaseStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a borrower may see about their own loans, assembled in one place so every borrower endpoint (the loan
 * view, documents, the safety assistant) draws the same line.
 *
 * <p><b>Whose loans.</b> Only ever the ids in the caller's own {@code User.loanIds}, looked up here from the
 * authenticated name. Nothing takes a loan id from the client without checking it against that set, and an id
 * outside it is treated exactly like one that does not exist.
 *
 * <p><b>What is never included.</b> The probability, the raw score, the model version, explanations, case
 * notes, case reasons, the flag, and who uploaded a document other than "you" or "your lender". The band and a
 * sentence on how it moved stand in for the score (design decision 1).
 */
@Service
public class BorrowerPortalService {

    static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    static final String NO_HISTORY = "No earlier check to compare with yet.";
    static final String CASE_DESCRIPTION =
            "Your loan is being reviewed. You don't need to do anything unless your loan officer contacts you.";
    static final String ANALYST_TITLE = "Loan officer";

    public record Status(RiskBand band, String label, String trend, Instant checkedAt) {
    }

    public record OpenCase(String status, String description, Instant openedAt) {
    }

    /** @param status null while the loan has not been scored; {@code openCase} null when nothing is open;
     *                {@code contact} null when the loan has no assigned analyst and the organization set no default */
    public record MyLoanView(String loanId, Status status, OpenCase openCase, BorrowerContactService.Contact contact) {
    }

    public record BorrowerDocument(Long id, String filename, long sizeBytes, Instant uploadedAt, boolean uploadedByYou) {
    }

    private final UserService userService;
    private final LoanScoreService loanScoreService;
    private final LoanCaseService loanCaseService;
    private final LoanAttachmentService loanAttachmentService;
    private final BorrowerContactService contacts;

    public BorrowerPortalService(UserService userService, LoanScoreService loanScoreService, LoanCaseService loanCaseService,
                                 LoanAttachmentService loanAttachmentService, BorrowerContactService contacts) {
        this.userService = userService;
        this.loanScoreService = loanScoreService;
        this.loanCaseService = loanCaseService;
        this.loanAttachmentService = loanAttachmentService;
        this.contacts = contacts;
    }

    /** The caller's own linked loan ids, in a stable order. */
    public List<String> ownLoanIds(Long tenantId, String username) {
        return caller(tenantId, username).getLoanIds().stream().sorted().toList();
    }

    public boolean owns(Long tenantId, String username, String loanId) {
        return loanId != null && caller(tenantId, username).getLoanIds().contains(loanId);
    }

    public List<MyLoanView> myLoans(Long tenantId, String username) {
        return ownLoanIds(tenantId, username).stream().map(loanId -> view(tenantId, loanId)).toList();
    }

    /** The contact for the first of the caller's loans with an assigned analyst, else the organization's
     * default: what the assistant panel shows. */
    public Optional<BorrowerContactService.Contact> contactFor(Long tenantId, String username) {
        return ownLoanIds(tenantId, username).stream()
                .map(loanId -> assignedAnalyst(tenantId, loanCaseService.openCase(tenantId, loanId)))
                .flatMap(Optional::stream).findFirst()
                .or(() -> contacts.find(tenantId));
    }

    /** Documents on a loan the caller owns; call {@link #owns} first. */
    public List<BorrowerDocument> documents(Long tenantId, String username, String loanId) {
        return loanAttachmentService.listFor(tenantId, loanId).stream().map(a -> document(a, username)).toList();
    }

    static BorrowerDocument document(LoanAttachment a, String username) {
        return new BorrowerDocument(a.getId(), a.getFilename(), a.getSizeBytes(), a.getUploadedAt(), username.equals(a.getUploadedBy()));
    }

    private MyLoanView view(Long tenantId, String loanId) {
        Status status = loanScoreService.findByTenantAndLoanId(tenantId, loanId).map(BorrowerPortalService::status).orElse(null);
        Optional<LoanCaseService.OpenCase> open = loanCaseService.openCase(tenantId, loanId);
        OpenCase openCase = open.map(c -> new OpenCase(caseLabel(c.status()), CASE_DESCRIPTION, c.openedAt())).orElse(null);
        return new MyLoanView(loanId, status, openCase, assignedAnalyst(tenantId, open).or(() -> contacts.find(tenantId)).orElse(null));
    }

    /** The open case's assigned analyst, if there is one and the account is still active. */
    private Optional<BorrowerContactService.Contact> assignedAnalyst(Long tenantId, Optional<LoanCaseService.OpenCase> open) {
        return open.map(LoanCaseService.OpenCase::assignedTo)
                .flatMap(assignee -> userService.findActiveStaff(tenantId, assignee))
                .map(analyst -> new BorrowerContactService.Contact(analyst.getUsername(), ANALYST_TITLE, analyst.getEmail()));
    }

    static Status status(LoanScoreRecord record) {
        RiskBand band = record.getBand() != null ? record.getBand() : RiskBand.of(record.getCalibratedProbability());
        return new Status(band, band.borrowerLabel(), trend(band, record.getPreviousBand(), record.getBandSince()), record.getComputedAt());
    }

    /** How the band has moved, in words and a month: never a number that could be read back into a score. */
    static String trend(RiskBand band, RiskBand previous, Instant since) {
        if (since == null) {
            return NO_HISTORY;
        }
        String month = MONTH.format(since);
        if (previous == null || previous == band) {
            return "Unchanged since " + month + ".";
        }
        return previous.ordinal() < band.ordinal()
                ? "Up from " + previous.borrowerLabel() + " in " + month + "."
                : "Improved from " + previous.borrowerLabel() + " in " + month + ".";
    }

    static String caseLabel(LoanCaseStatus status) {
        return switch (status) {
            case NEW -> "Received";
            case REVIEWED -> "Under review";
            case ESCALATED -> "With a senior reviewer";
            case CLEARED -> "Closed";
        };
    }

    private User caller(Long tenantId, String username) {
        return userService.findByOrganizationAndUsername(tenantId, username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }
}
