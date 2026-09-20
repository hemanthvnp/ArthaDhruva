package com.arthadhruva.riskengine.workflow;

import java.io.IOException;
import java.io.InputStream;

/**
 * A deliberate seam between {@link LoanAttachmentController} and wherever attachment bytes
 * actually live -- {@link LocalFileStorageService} is the only implementation today (local disk,
 * fine for a single-instance deployment), but swapping to S3-compatible object storage later is
 * a new implementation of this interface, not a rewrite of every caller. Same "pluggable, sensible
 * local default" philosophy already used for the LLM provider (via LiteLLM) and every secret in
 * application.properties.
 */
public interface FileStorageService {

    /** @return an opaque storage path/key the caller must persist and pass back to {@link
     * #retrieve} -- callers must not assume anything about its structure (e.g. that it's a
     * filesystem path), since a future object-storage implementation would return an object key
     * instead. */
    StoredFile store(Long tenantId, String originalFilename, InputStream content) throws IOException;

    InputStream retrieve(String storagePath) throws IOException;

    record StoredFile(String storagePath, long sizeBytes) {
    }
}
