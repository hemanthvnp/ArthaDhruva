package com.arthadhruva.riskengine.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** The {@code workflow} module's façade for {@link LoanAttachment} -- {@link
 * LoanAttachmentRepository} is private to this package; {@link LoanAttachmentController} goes
 * through here, which in turn is the only caller of {@link FileStorageService}. */
@Service
public class LoanAttachmentService {

    private final LoanAttachmentRepository loanAttachmentRepository;
    private final FileStorageService fileStorageService;
    private final long maxSizeBytes;

    public LoanAttachmentService(LoanAttachmentRepository loanAttachmentRepository, FileStorageService fileStorageService,
                                  @Value("${attachments.max-size-bytes}") long maxSizeBytes) {
        this.loanAttachmentRepository = loanAttachmentRepository;
        this.fileStorageService = fileStorageService;
        this.maxSizeBytes = maxSizeBytes;
    }

    public LoanAttachment upload(Long tenantId, String loanId, MultipartFile file, String uploadedBy) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new IllegalArgumentException("File exceeds the " + (maxSizeBytes / (1024 * 1024)) + "MB size limit.");
        }

        FileStorageService.StoredFile stored = fileStorageService.store(tenantId, file.getOriginalFilename(), file.getInputStream());
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        LoanAttachment attachment = new LoanAttachment(tenantId, loanId, file.getOriginalFilename(),
                contentType, stored.sizeBytes(), stored.storagePath(), uploadedBy);
        return loanAttachmentRepository.save(attachment);
    }

    public List<LoanAttachment> listFor(Long tenantId, String loanId) {
        return loanAttachmentRepository.findByTenantIdAndLoanIdOrderByUploadedAtDesc(tenantId, loanId);
    }

    public Optional<LoanAttachment> find(Long tenantId, String loanId, Long attachmentId) {
        return loanAttachmentRepository.findByIdAndTenantIdAndLoanId(attachmentId, tenantId, loanId);
    }

    public InputStream openStream(LoanAttachment attachment) throws IOException {
        return fileStorageService.retrieve(attachment.getStoragePath());
    }
}
