package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.workflow.LoanAttachmentService.DocumentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.arthadhruva.riskengine.workflow.LoanCaseStatus.CLEARED;
import static com.arthadhruva.riskengine.workflow.LoanCaseStatus.ESCALATED;
import static com.arthadhruva.riskengine.workflow.LoanCaseStatus.NEW;
import static com.arthadhruva.riskengine.workflow.LoanCaseStatus.REVIEWED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRulesTest {

    @Test
    void caseStatusFollowsTheTransitionTable() {
        LoanCaseStatus[][] allowed = {
                {NEW, REVIEWED}, {NEW, ESCALATED}, {NEW, CLEARED},
                {REVIEWED, ESCALATED}, {REVIEWED, CLEARED},
                {ESCALATED, REVIEWED}, {ESCALATED, CLEARED},
                {CLEARED, REVIEWED}};
        for (LoanCaseStatus from : LoanCaseStatus.values()) {
            for (LoanCaseStatus to : LoanCaseStatus.values()) {
                boolean expected = from == to;
                for (LoanCaseStatus[] pair : allowed) {
                    expected |= pair[0] == from && pair[1] == to;
                }
                assertEquals(expected, from.canTransitionTo(to), from + " -> " + to);
            }
        }
        // nothing returns to NEW, and a cleared case cannot be escalated without being reopened first
        assertFalse(REVIEWED.canTransitionTo(NEW));
        assertFalse(CLEARED.canTransitionTo(ESCALATED));
    }

    @Test
    void consequentialTransitionsNeedAReason() {
        assertTrue(NEW.requiresReason(ESCALATED));
        assertTrue(REVIEWED.requiresReason(ESCALATED));
        assertTrue(ESCALATED.requiresReason(CLEARED));
        assertTrue(CLEARED.requiresReason(REVIEWED), "reopening");
        assertFalse(NEW.requiresReason(REVIEWED));
        assertFalse(NEW.requiresReason(CLEARED));
        assertFalse(ESCALATED.requiresReason(ESCALATED), "reassigning an escalated case is not a new escalation");
        assertFalse(ESCALATED.requiresReason(REVIEWED));
    }

    /** The type comes from the bytes, never from the file name or the browser's claim. */
    @Test
    void documentsAreIdentifiedByTheirContent() {
        assertEquals(Optional.of(DocumentType.PDF), LoanAttachmentService.detect("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.of(DocumentType.PNG), LoanAttachmentService.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}));
        assertEquals(Optional.of(DocumentType.JPEG), LoanAttachmentService.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals(Optional.of(DocumentType.OFFICE_OPEN_XML), LoanAttachmentService.detect(new byte[]{'P', 'K', 3, 4, 20, 0}));
        assertEquals(Optional.of(DocumentType.TEXT), LoanAttachmentService.detect("loan,balance\nL1,1000\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.of(DocumentType.TEXT), LoanAttachmentService.detect("Zinssatz geändert – 4,5 %".getBytes(StandardCharsets.UTF_8)));

        // A Windows executable, raw binary, and text in a legacy encoding are none of the above.
        assertEquals(Optional.empty(), LoanAttachmentService.detect(new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}));
        assertEquals(Optional.empty(), LoanAttachmentService.detect(new byte[]{1, 2, 0, 4}));
        assertEquals(Optional.empty(), LoanAttachmentService.detect("ge\u00e4ndert".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(Optional.empty(), LoanAttachmentService.detect(new byte[0]));
    }

    /** A multi-byte character cut in half by the 8 KB sample is still text. */
    @Test
    void textSplitMidCharacterIsStillText() {
        byte[] full = "résumé".getBytes(StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 1);   // ends inside the last 'é'
        assertEquals(Optional.of(DocumentType.TEXT), LoanAttachmentService.detect(cut));
    }

    @Test
    void aZipIsServedOnlyAsAnOfficeDocument() {
        assertTrue(LoanAttachmentService.contentTypeFor(DocumentType.OFFICE_OPEN_XML, "Appraisal.DOCX").orElseThrow().contains("wordprocessingml"));
        assertTrue(LoanAttachmentService.contentTypeFor(DocumentType.OFFICE_OPEN_XML, "rates.xlsx").orElseThrow().contains("spreadsheetml"));
        assertEquals(Optional.empty(), LoanAttachmentService.contentTypeFor(DocumentType.OFFICE_OPEN_XML, "payload.zip"));
        assertEquals(Optional.empty(), LoanAttachmentService.contentTypeFor(DocumentType.OFFICE_OPEN_XML, "macro.jar"));
        assertEquals("text/csv; charset=utf-8", LoanAttachmentService.contentTypeFor(DocumentType.TEXT, "loans.csv").orElseThrow());
        assertEquals("application/pdf", LoanAttachmentService.contentTypeFor(DocumentType.PDF, "anything.exe").orElseThrow());
    }

    @Test
    void fileNamesAreDisplayNamesNeverPaths() {
        assertEquals("passwd", LoanAttachmentService.safeFilename("../../etc/passwd"));
        assertEquals("report.pdf", LoanAttachmentService.safeFilename("C:\\Users\\ana\\report.pdf"));
        assertEquals("ab.pdf", LoanAttachmentService.safeFilename("a\r\nb\".pdf"));
        assertEquals("attachment", LoanAttachmentService.safeFilename(".."));
        assertEquals("attachment", LoanAttachmentService.safeFilename("  "));
        assertEquals("attachment", LoanAttachmentService.safeFilename(null));
        assertEquals(200, LoanAttachmentService.safeFilename("x".repeat(500) + ".pdf").length());
    }
}
