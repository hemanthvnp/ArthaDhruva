package com.arthadhruva.riskengine.workflow;

import java.io.IOException;
import java.io.InputStream;

/**
 * The seam between attachments and wherever their bytes live. {@link LocalFileStorageService} (local
 * disk) is the implementation today; S3-compatible object storage would be another implementation, not
 * a change to any caller. Storage paths are opaque keys the caller persists and passes back.
 */
public interface FileStorageService {

    /** Stores the content and returns its opaque key, size and SHA-256. */
    StoredFile store(Long tenantId, String originalFilename, InputStream content) throws IOException;

    InputStream retrieve(String storagePath) throws IOException;

    /** Removes stored content; a missing object is not an error. */
    void delete(String storagePath) throws IOException;

    record StoredFile(String storagePath, long sizeBytes, String sha256) {
    }
}
