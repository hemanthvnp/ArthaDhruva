package com.arthadhruva.riskengine.borrower;

import com.arthadhruva.riskengine.event.LoanDocumentAddedEvent;
import com.arthadhruva.riskengine.tenant.TenantContext;
import com.arthadhruva.riskengine.workflow.AttachmentDownloads;
import com.arthadhruva.riskengine.workflow.LoanAttachment;
import com.arthadhruva.riskengine.workflow.LoanAttachmentService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * A borrower's documents: list, upload and download attachments on a loan in the caller's own loan ids, under
 * /my/** (any signed-in ANALYST/ADMIN/CLIENT, each limited to the loans linked to their own account).
 *
 * <p>Deliberately separate from {@code LoanAttachmentController}, whose endpoints are tenant-wide for staff
 * (design decision 5): no case id, no dependency on a case existing, and the ownership check comes first, so a
 * loan outside the caller's set gets the same 404 whether or not it exists, in this organization or another,
 * and nothing is stored. Storage, type detection, quota and malware scanning are the shared
 * {@link LoanAttachmentService}'s, so a borrower's upload is held to exactly the rules an analyst's is.
 */
@RestController
public class BorrowerDocumentsController {

    private static final ResponseEntity<Map<String, String>> UNKNOWN_LOAN =
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown loan."));

    private final BorrowerPortalService portal;
    private final LoanAttachmentService attachments;
    private final ApplicationEventPublisher events;

    public BorrowerDocumentsController(BorrowerPortalService portal, LoanAttachmentService attachments, ApplicationEventPublisher events) {
        this.portal = portal;
        this.attachments = attachments;
        this.events = events;
    }

    @GetMapping("/my/loans/{loanId}/documents")
    public ResponseEntity<?> list(@PathVariable String loanId, Authentication authentication) {
        Long tenantId = TenantContext.get();
        if (!portal.owns(tenantId, authentication.getName(), loanId)) {
            return UNKNOWN_LOAN;
        }
        return ResponseEntity.ok(portal.documents(tenantId, authentication.getName(), loanId));
    }

    @PostMapping("/my/loans/{loanId}/documents")
    public ResponseEntity<?> upload(@PathVariable String loanId, @RequestParam("file") MultipartFile file,
                                    Authentication authentication) throws IOException {
        Long tenantId = TenantContext.get();
        if (!portal.owns(tenantId, authentication.getName(), loanId)) {
            return UNKNOWN_LOAN;
        }
        LoanAttachment stored = attachments.upload(tenantId, loanId, file, authentication.getName());
        events.publishEvent(new LoanDocumentAddedEvent(tenantId, loanId, authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BorrowerPortalService.document(stored, authentication.getName()));
    }

    @GetMapping("/my/loans/{loanId}/documents/{documentId}/download")
    public ResponseEntity<?> download(@PathVariable String loanId, @PathVariable Long documentId,
                                      Authentication authentication) throws IOException {
        Long tenantId = TenantContext.get();
        if (!portal.owns(tenantId, authentication.getName(), loanId)) {
            return UNKNOWN_LOAN;
        }
        LoanAttachment attachment = attachments.find(tenantId, loanId, documentId).orElse(null);
        if (attachment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Document not found."));
        }
        return AttachmentDownloads.stream(attachment, attachments);
    }

    @ExceptionHandler(LoanAttachmentService.UploadRejected.class)
    public ResponseEntity<Map<String, String>> onRejected(LoanAttachmentService.UploadRejected e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }
}
