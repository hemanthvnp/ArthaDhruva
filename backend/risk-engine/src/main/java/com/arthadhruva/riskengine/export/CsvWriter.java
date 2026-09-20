package com.arthadhruva.riskengine.export;

import java.util.List;

/**
 * Minimal RFC 4180 CSV writer -- no external dependency needed for output this simple (flat
 * rows of scalar values, no need for streaming/large-file support at this app's data volumes).
 * A shared utility rather than duplicated per export endpoint (see {@code score.ScoreController}
 * and {@code workflow.LoanCaseController}).
 */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        writeRow(sb, headers);
        for (List<String> row : rows) {
            writeRow(sb, row);
        }
        return sb.toString();
    }

    private static void writeRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append("\r\n");
    }

    /** Quotes a field only when necessary (contains a comma, quote, or newline), doubling any
     * embedded quotes -- the standard RFC 4180 escaping rule. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
