package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** ANALYST/ADMIN only (the default access rule), same as the rest of the case-workflow
 * endpoints -- an attachment on the internal review case is not something a CLIENT sees. */
@RestController
public class LoanAttachmentController {

    private final LoanAttachmentService loanAttachmentService;

    public LoanAttachmentController(LoanAttachmentService loanAttachmentService) {
        this.loanAttachmentService = loanAttachmentService;
    }

    @PostMapping("/loans/{loanId}/attachments")
    public ResponseEntity<?> upload(@PathVariable String loanId, @RequestParam("file") MultipartFile file,
                                     Authentication authentication) throws IOException {
        try {
            LoanAttachment attachment = loanAttachmentService.upload(TenantContext.get(), loanId, file, authentication.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentView.of(attachment));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/loans/{loanId}/attachments")
    public List<AttachmentView> list(@PathVariable String loanId) {
        return loanAttachmentService.listFor(TenantContext.get(), loanId).stream().map(AttachmentView::of).toList();
    }

    @GetMapping("/loans/{loanId}/attachments/{attachmentId}/download")
    public ResponseEntity<?> download(@PathVariable String loanId, @PathVariable Long attachmentId) throws IOException {
        var attachment = loanAttachmentService.find(TenantContext.get(), loanId, attachmentId).orElse(null);
        if (attachment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Attachment not found."));
        }
        InputStreamResource body = new InputStreamResource(loanAttachmentService.openStream(attachment));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(attachment.getFilename()).build().toString())
                .contentLength(attachment.getSizeBytes())
                .body(body);
    }

    public record AttachmentView(Long id, String filename, String contentType, long sizeBytes,
                                  String uploadedBy, Instant uploadedAt) {
        static AttachmentView of(LoanAttachment a) {
            return new AttachmentView(a.getId(), a.getFilename(), a.getContentType(), a.getSizeBytes(),
                    a.getUploadedBy(), a.getUploadedAt());
        }
    }
}
