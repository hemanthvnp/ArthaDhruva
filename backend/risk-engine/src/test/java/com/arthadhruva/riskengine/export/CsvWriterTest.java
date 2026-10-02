package com.arthadhruva.riskengine.export;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {

    /** A cell a spreadsheet would run as a formula is turned into text. */
    @Test
    void formulaCellsAreNeutralized() {
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\")\"", CsvWriter.escape("=HYPERLINK(\"http://evil\")"));
        assertEquals("'+cmd|' /C calc'!A0", CsvWriter.escape("+cmd|' /C calc'!A0"));
        assertEquals("'@SUM(A1)", CsvWriter.escape("@SUM(A1)"));
        assertEquals("'-2+3", CsvWriter.escape("-2+3"));
        assertEquals("'\tx", CsvWriter.escape("\tx"));
    }

    @Test
    void numbersAreLeftAsNumbers() {
        for (String number : List.of("0", "42", "-42", "+3.5", "-0.0125", "1e-5", "-2.5E+10")) {
            assertEquals(number, CsvWriter.escape(number));
        }
    }

    @Test
    void quotingFollowsRfc4180() {
        assertEquals("plain", CsvWriter.escape("plain"));
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escape("say \"hi\""));
        assertEquals("\"two\nlines\"", CsvWriter.escape("two\nlines"));
        assertEquals("", CsvWriter.escape(null));
        assertEquals("a,b\r\n1,\"x,y\"\r\n", CsvWriter.write(List.of("a", "b"), List.of(List.of("1", "x,y"))));
    }

    @Test
    void streamingWritesTheHeaderThenEachRow() {
        StringWriter out = new StringWriter();
        CsvWriter.Streaming csv = new CsvWriter.Streaming(out, List.of("loanId", "note"));
        csv.row(List.of("L1", "=1+1"));
        csv.row(List.of("L2", "fine"));
        assertEquals("loanId,note\r\nL1,'=1+1\r\nL2,fine\r\n", out.toString());
    }
}
