package com.arthadhruva.riskengine.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Attachment bytes on local disk under {@code attachments.storage-dir}, one subdirectory per tenant.
 * Stored names are random UUIDs: the original filename is metadata in the database only and never
 * reaches the filesystem, so no user-supplied name can influence where a file lands. Right for a
 * single-instance deployment; see {@link FileStorageService} for the object-storage seam.
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
        Path target = tenantDir.resolve(UUID.randomUUID().toString());
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long size;
        try (DigestInputStream in = new DigestInputStream(content, sha256)) {
            size = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(rootDir.relativize(target).toString(), size, HexFormat.of().formatHex(sha256.digest()));
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        return Files.newInputStream(resolveInsideRoot(storagePath));
    }

    @Override
    public void delete(String storagePath) throws IOException {
        Files.deleteIfExists(resolveInsideRoot(storagePath));
    }

    /** Storage paths come only from {@link #store}; this guards against a corrupted row pointing outside
     * the root, which would otherwise read or delete an arbitrary file. */
    private Path resolveInsideRoot(String storagePath) throws IOException {
        Path resolved = rootDir.resolve(storagePath).normalize();
        if (!resolved.startsWith(rootDir) || resolved.equals(rootDir)) {
            throw new IOException("Resolved path escapes storage root");
        }
        return resolved;
    }
}
