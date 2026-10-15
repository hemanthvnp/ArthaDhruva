package com.arthadhruva.riskengine.workflow;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** How a stored attachment is served, whoever asked for it: always as a download ({@code attachment}), with
 * the type detected at upload and nosniff, so a browser never renders stored content inline. */
public final class AttachmentDownloads {

    private AttachmentDownloads() {
    }

    public static ResponseEntity<?> stream(LoanAttachment attachment, LoanAttachmentService service) throws IOException {
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
                .body(new InputStreamResource(service.openStream(attachment)));
    }
}
