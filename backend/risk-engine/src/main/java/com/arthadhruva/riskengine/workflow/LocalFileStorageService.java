package com.arthadhruva.riskengine.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Writes attachment bytes to local disk under {@code attachments.storage-dir}, one
 * subdirectory per tenant. Fine for a single-instance deployment (this app's current shape);
 * see {@link FileStorageService}'s doc for why this is behind an interface at all.
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path rootDir;

    public LocalFileStorageService(@Value("${attachments.storage-dir}") String storageDir) throws IOException {
        this.rootDir = Path.of(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(rootDir);
    }

    @Override
    public StoredFile store(Long tenantId, String originalFilename, InputStream content) throws IOException {
        Path tenantDir = rootDir.resolve(String.valueOf(tenantId));
        Files.createDirectories(tenantDir);

        // UUID-prefixed so two uploads of the same filename never collide, without needing to
        // sanitize/reject the original name -- it's kept, just not relied on for uniqueness.
        String safeName = Path.of(originalFilename).getFileName().toString();
        Path target = tenantDir.resolve(UUID.randomUUID() + "-" + safeName);

        long sizeBytes = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(rootDir.relativize(target).toString(), sizeBytes);
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        Path resolved = rootDir.resolve(storagePath).normalize();
        if (!resolved.startsWith(rootDir)) {
            // storagePath is only ever a value this service itself produced and the caller
            // persisted verbatim (see FileStorageService's doc) -- this can only fire on data
            // corruption or a bug elsewhere, not attacker-controlled input, but failing loudly
            // beats silently reading outside the intended root.
            throw new IOException("Resolved path escapes storage root: " + storagePath);
        }
        return Files.newInputStream(resolved);
    }
}
