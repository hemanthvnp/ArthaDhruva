package com.arthadhruva.riskengine.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The {@code workflow} module's facade for loan attachments.
 *
 * <p>Uploads are accepted only as one of a fixed set of document types, identified from the file's
 * own leading bytes -- the client's Content-Type header is ignored, so an HTML or executable renamed to
 * {@code .pdf} is refused. Each tenant has a storage quota; files are optionally malware-scanned
 * (fail-closed); the stored bytes are hashed; and if the database write fails the stored file is removed
 * again, so no orphaned bytes accumulate.
 */
@Service
public class LoanAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(LoanAttachmentService.class);

    /** Detected type -> content type served on download. */
    enum DocumentType {
        PDF("application/pdf"), PNG("image/png"), JPEG("image/jpeg"),
        OFFICE_OPEN_XML("application/zip"), TEXT("text/plain; charset=utf-8");

        final String contentType;

        DocumentType(String contentType) {
            this.contentType = contentType;
        }
    }

    private final LoanAttachmentRepository loanAttachmentRepository;
    private final FileStorageService fileStorageService;
    private final MalwareScanner scanner;
    private final long maxSizeBytes;
    private final long tenantQuotaBytes;

    public LoanAttachmentService(LoanAttachmentRepository loanAttachmentRepository, FileStorageService fileStorageService,
                                 MalwareScanner scanner,
                                 @Value("${attachments.max-size-bytes}") long maxSizeBytes,
                                 @Value("${attachments.tenant-quota-bytes:1073741824}") long tenantQuotaBytes) {
        this.loanAttachmentRepository = loanAttachmentRepository;
        this.fileStorageService = fileStorageService;
        this.scanner = scanner;
        this.maxSizeBytes = maxSizeBytes;
        this.tenantQuotaBytes = tenantQuotaBytes;
    }

    /** A rejected upload, with the HTTP status that describes why. */
    public static class UploadRejected extends RuntimeException {
        private final int status;

        UploadRejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    public LoanAttachment upload(Long tenantId, String loanId, MultipartFile file, String uploadedBy) throws IOException {
        if (file.isEmpty()) {
            throw new UploadRejected(400, "Uploaded file is empty.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new UploadRejected(413, "File exceeds the " + (maxSizeBytes / (1024 * 1024)) + " MB size limit.");
        }
        if (loanAttachmentRepository.totalBytes(tenantId) + file.getSize() > tenantQuotaBytes) {
            throw new UploadRejected(413, "Your organization's attachment storage quota is full.");
        }
        DocumentType type;
        try (InputStream in = file.getInputStream()) {
            type = detect(in.readNBytes(8192)).orElseThrow(() -> new UploadRejected(415,
                    "Unsupported file type. Accepted: PDF, PNG, JPEG, DOCX/XLSX, plain text or CSV."));
        }
        if (scanner.enabled()) {
            MalwareScanner.Verdict verdict;
            try (InputStream in = file.getInputStream()) {
                verdict = scanner.scan(in);
            }
            if (verdict == MalwareScanner.Verdict.INFECTED) {
                throw new UploadRejected(422, "The file was rejected by the malware scanner.");
            }
            if (verdict != MalwareScanner.Verdict.CLEAN) {
                throw new UploadRejected(503, "Malware scanning is unavailable; please try again later.");
            }
        }
        String filename = safeFilename(file.getOriginalFilename());
        String contentType = contentTypeFor(type, filename).orElseThrow(() -> new UploadRejected(415,
                "Archives are accepted only as Word (.docx) or Excel (.xlsx) documents."));
        FileStorageService.StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorageService.store(tenantId, filename, in);
        }
        try {
            return loanAttachmentRepository.save(new LoanAttachment(tenantId, loanId, filename,
                    contentType, stored.sizeBytes(), stored.storagePath(), uploadedBy, stored.sha256()));
        } catch (RuntimeException e) {
            try {
                fileStorageService.delete(stored.storagePath());
            } catch (IOException cleanup) {
                log.warn("Could not remove orphaned attachment bytes at {}", stored.storagePath(), cleanup);
            }
            throw e;
        }
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

    /** Removes the record, then the bytes (a failure to remove bytes leaves an unreferenced file, never a
     * record pointing at nothing). */
    public boolean delete(Long tenantId, String loanId, Long attachmentId) throws IOException {
        Optional<LoanAttachment> attachment = find(tenantId, loanId, attachmentId);
        if (attachment.isEmpty()) {
            return false;
        }
        loanAttachmentRepository.delete(attachment.get());
        fileStorageService.delete(attachment.get().getStoragePath());
        return true;
    }

    /** The served content type. A ZIP container is accepted only as an Office Open XML document. */
    static Optional<String> contentTypeFor(DocumentType type, String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case OFFICE_OPEN_XML -> lower.endsWith(".docx")
                    ? Optional.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    : lower.endsWith(".xlsx")
                    ? Optional.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    : Optional.empty();
            case TEXT -> Optional.of(lower.endsWith(".csv") ? "text/csv; charset=utf-8" : type.contentType);
            default -> Optional.of(type.contentType);
        };
    }

    /** Identifies a document from its leading bytes. Text must be valid UTF-8 without NUL bytes. */
    static Optional<DocumentType> detect(byte[] head) {
        if (startsWith(head, 0x25, 0x50, 0x44, 0x46, 0x2D)) {                 // %PDF-
            return Optional.of(DocumentType.PDF);
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) { // PNG signature
            return Optional.of(DocumentType.PNG);
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {                               // JPEG SOI
            return Optional.of(DocumentType.JPEG);
        }
        if (startsWith(head, 0x50, 0x4B, 0x03, 0x04)) {                         // ZIP: DOCX/XLSX
            return Optional.of(DocumentType.OFFICE_OPEN_XML);
        }
        if (head.length > 0 && isUtf8Text(head)) {
            return Optional.of(DocumentType.TEXT);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8Text(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        // The sample may end mid-character; trim up to 3 bytes of a possibly split sequence.
        for (int trim = 0; trim <= Math.min(3, head.length - 1); trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Arrays.copyOf(head, head.length - trim)));
                return true;
            } catch (CharacterCodingException e) {
                // try a shorter prefix
            }
        }
        return false;
    }

    /** Display name only (never a filesystem path): last path segment, control characters removed, capped. */
    static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "attachment";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "attachment";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
