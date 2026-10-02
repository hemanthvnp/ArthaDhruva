package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Attachments on a loan's internal review case: ANALYST/ADMIN to upload and read (the default access
 * rule), ADMIN to delete (under /admin/**, so every deletion is an audited admin action). */
@RestController
public class LoanAttachmentController {

    private final LoanAttachmentService loanAttachmentService;
    private final LoanCaseService loanCaseService;

    public LoanAttachmentController(LoanAttachmentService loanAttachmentService, LoanCaseService loanCaseService) {
        this.loanAttachmentService = loanAttachmentService;
        this.loanCaseService = loanCaseService;
    }

    @PostMapping("/loans/{loanId}/attachments")
    public ResponseEntity<?> upload(@PathVariable String loanId, @RequestParam("file") MultipartFile file,
                                    Authentication authentication) throws IOException {
        Long tenantId = TenantContext.get();
        if (!loanCaseService.isKnownLoan(tenantId, loanId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown loan"));
        }
        LoanAttachment attachment = loanAttachmentService.upload(tenantId, loanId, file, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentView.of(attachment));
    }

    @GetMapping("/loans/{loanId}/attachments")
    public List<AttachmentView> list(@PathVariable String loanId) {
        return loanAttachmentService.listFor(TenantContext.get(), loanId).stream().map(AttachmentView::of).toList();
    }

    /** Always served as a download ({@code attachment}), with the type detected at upload and nosniff, so a
     * browser never renders stored content inline. */
    @GetMapping("/loans/{loanId}/attachments/{attachmentId}/download")
    public ResponseEntity<?> download(@PathVariable String loanId, @PathVariable Long attachmentId) throws IOException {
        var attachment = loanAttachmentService.find(TenantContext.get(), loanId, attachmentId).orElse(null);
        if (attachment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(attachment.getContentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(attachment.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentLength(attachment.getSizeBytes())
                .body(new InputStreamResource(loanAttachmentService.openStream(attachment)));
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
