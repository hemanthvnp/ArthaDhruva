package com.arthadhruva.riskengine.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An upload spans two stores that share no transaction: the bytes on disk and the record in Postgres. These
 * tests hold it to the compensating action that stands in for one: bytes whose record could not be written
 * are removed again, and a deletion removes the record before the bytes.
 */
class LoanAttachmentCompensationTest {

    private final LoanAttachmentRepository repository = mock(LoanAttachmentRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final LoanAttachmentService service = new LoanAttachmentService(repository, storage, scanner, 1024 * 1024, 10 * 1024 * 1024);

    private static MockMultipartFile note() {
        return new MockMultipartFile("file", "note.txt", "text/plain", "reviewed".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bytesWhoseRecordCouldNotBeWrittenAreRemovedAgain() throws Exception {
        when(storage.store(eq(7L), anyString(), any())).thenReturn(new FileStorageService.StoredFile("7/abc", 8, "sha"));
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("the database refused the record"));

        assertThrows(DataIntegrityViolationException.class, () -> service.upload(7L, "L-1", note(), "ana"),
                "the caller still learns that the upload failed");
        verify(storage).delete("7/abc");
    }

    @Test
    void aStoredUploadIsNotCompensated() throws Exception {
        when(storage.store(eq(7L), anyString(), any())).thenReturn(new FileStorageService.StoredFile("7/abc", 8, "sha"));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.upload(7L, "L-1", note(), "ana");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aRefusedUploadStoresNothingToCompensate() throws Exception {
        when(repository.totalBytes(7L)).thenReturn(10L * 1024 * 1024);

        assertThrows(LoanAttachmentService.UploadRejected.class, () -> service.upload(7L, "L-1", note(), "ana"));
        verify(storage, never()).store(any(), anyString(), any());
    }

    @Test
    void aDeletionRemovesTheRecordBeforeTheBytes() throws Exception {
        LoanAttachment attachment = new LoanAttachment(7L, "L-1", "note.txt", "text/plain", 8, "7/abc", "ana", "sha");
        when(repository.findByIdAndTenantIdAndLoanId(3L, 7L, "L-1")).thenReturn(java.util.Optional.of(attachment));

        service.delete(7L, "L-1", 3L);
        var order = inOrder(repository, storage);
        order.verify(repository).delete(attachment);
        order.verify(storage).delete("7/abc");
    }
}
