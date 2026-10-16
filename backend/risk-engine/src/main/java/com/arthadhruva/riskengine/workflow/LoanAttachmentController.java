package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.event.LoanDocumentAddedEvent;
import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Attachments on a loan's internal review case: ANALYST/ADMIN to upload and read (the default access
 * rule), ADMIN to delete (under /admin/**, so every deletion is an audited admin action).
 *
 * <p><b>Download links (valet key).</b> Someone allowed to read an attachment can ask for a link to it: a
 * signed token naming that one attachment, good for {@value #LINK_SECONDS} seconds. Whoever presents the
 * token gets that file and nothing else, with no session: it can be handed to a plain browser download, or
 * to a server that should fetch one document without being given an account. Asking for a link is the
 * access decision and is audited with the person who asked; redeeming one is logged. The token is a
 * purpose-scoped JWT, so it is never accepted as a session, and the same token is good until it expires
 * (a download that is retried or resumed must not fail).
 */
@RestController
public class LoanAttachmentController {

    private static final Logger log = LoggerFactory.getLogger(LoanAttachmentController.class);
    static final String LINK_PURPOSE = "attachment-download";
    static final int LINK_SECONDS = 60;

    private final LoanAttachmentService loanAttachmentService;
    private final LoanCaseService loanCaseService;
    private final JwtService jwt;
    private final ApplicationEventPublisher events;

    public LoanAttachmentController(LoanAttachmentService loanAttachmentService, LoanCaseService loanCaseService, JwtService jwt,
                                    ApplicationEventPublisher events) {
        this.loanAttachmentService = loanAttachmentService;
        this.loanCaseService = loanCaseService;
        this.jwt = jwt;
        this.events = events;
    }

    @PostMapping("/loans/{loanId}/attachments")
    public ResponseEntity<?> upload(@PathVariable String loanId, @RequestParam("file") MultipartFile file,
                                    Authentication authentication) throws IOException {
        Long tenantId = TenantContext.get();
        if (!loanCaseService.isKnownLoan(tenantId, loanId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown loan"));
        }
        LoanAttachment attachment = loanAttachmentService.upload(tenantId, loanId, file, authentication.getName());
        events.publishEvent(new LoanDocumentAddedEvent(tenantId, loanId, authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentView.of(attachment));
    }

    @GetMapping("/loans/{loanId}/attachments")
    public List<AttachmentView> list(@PathVariable String loanId) {
        return loanAttachmentService.listFor(TenantContext.get(), loanId).stream().map(AttachmentView::of).toList();
    }

    /** Served as a download, never inline (see {@link AttachmentDownloads}). */
    @GetMapping("/loans/{loanId}/attachments/{attachmentId}/download")
    public ResponseEntity<?> download(@PathVariable String loanId, @PathVariable Long attachmentId) throws IOException {
        var attachment = loanAttachmentService.find(TenantContext.get(), loanId, attachmentId).orElse(null);
        if (attachment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
        }
        return AttachmentDownloads.stream(attachment, loanAttachmentService);
    }

    /**
     * A short-lived link to one attachment. The response names the token {@code downloadToken} so the audit
     * trail, which records what a POST returned, redacts it.
     */
    @PostMapping("/loans/{loanId}/attachments/{attachmentId}/download-link")
    public ResponseEntity<?> downloadLink(@PathVariable String loanId, @PathVariable Long attachmentId) {
        Long tenantId = TenantContext.get();
        if (loanAttachmentService.find(tenantId, loanId, attachmentId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
        }
        String token = jwt.issueClaimsToken(LINK_PURPOSE, Duration.ofSeconds(LINK_SECONDS),
                Map.of("org", tenantId, "loan", loanId, "att", attachmentId));
        return ResponseEntity.ok(Map.of("path", "/v1/attachment-downloads", "downloadToken", token, "expiresInSeconds", LINK_SECONDS));
    }

    /**
     * Redeems a download link; public, because the token is the credential (SecurityConfig). It names the
     * organization as well as the file, so the lookup still goes through row-level security.
     */
    @NotAudited
    @GetMapping("/attachment-downloads")
    public ResponseEntity<?> redeem(@RequestParam("token") String token) throws IOException {
        Claims claims = jwt.parseClaimsToken(token, LINK_PURPOSE).orElse(null);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This download link is invalid or has expired."));
        }
        Long tenantId = claims.get("org", Long.class);
        String loanId = claims.get("loan", String.class);
        Long attachmentId = claims.get("att", Long.class);
        Long callerTenant = TenantContext.getOptional().orElse(null);
        LoanAttachment attachment;
        TenantContext.set(tenantId);
        try {
            attachment = loanAttachmentService.find(tenantId, loanId, attachmentId).orElse(null);
        } finally {
            if (callerTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(callerTenant);
            }
        }
        if (attachment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
        }
        log.info("Attachment {} of loan {} (tenant {}) downloaded with a link", attachmentId, loanId, tenantId);
        return AttachmentDownloads.stream(attachment, loanAttachmentService);
    }

    @DeleteMapping("/admin/loans/{loanId}/attachments/{attachmentId}")
    public ResponseEntity<?> delete(@PathVariable String loanId, @PathVariable Long attachmentId) throws IOException {
        return loanAttachmentService.delete(TenantContext.get(), loanId, attachmentId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
    }

    @ExceptionHandler(LoanAttachmentService.UploadRejected.class)
    public ResponseEntity<Map<String, String>> onRejected(LoanAttachmentService.UploadRejected e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    public record AttachmentView(Long id, String filename, String contentType, long sizeBytes,
                                 String uploadedBy, Instant uploadedAt, String sha256) {
        static AttachmentView of(LoanAttachment a) {
            return new AttachmentView(a.getId(), a.getFilename(), a.getContentType(), a.getSizeBytes(),
                    a.getUploadedBy(), a.getUploadedAt(), a.getContentSha256());
        }
    }
}
