package com.arthadhruva.riskengine.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RFC 4180 CSV output, with spreadsheet formula injection neutralized.
 *
 * <p>Exported cells include user-controlled text (loan ids from a core system, assignee names). A cell
 * beginning with {@code = + - @}, a tab or a carriage return is evaluated as a formula by Excel and
 * LibreOffice -- {@code =HYPERLINK(...)} or DDE payloads would run on the machine of whoever opens the
 * export. Such cells are prefixed with a single quote, which spreadsheets display as text (OWASP CSV
 * Injection guidance). Plain numbers, including negatives, are left untouched.
 */
public final class CsvWriter {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private CsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    /** Incremental writer for exports that stream rows as they are read. */
    public static final class Streaming {
        private final Writer out;

        public Streaming(Writer out, List<String> headers) {
            this.out = out;
            row(headers);
        }

        public void row(List<String> values) {
            StringBuilder sb = new StringBuilder();
            appendRow(sb, values);
            try {
                out.write(sb.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void appendRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append("\r\n");
    }

    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String value = neutralizeFormula(raw);
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return needsQuoting ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String neutralizeFormula(String value) {
        if (value.isEmpty() || NUMBER.matcher(value).matches()) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }
}
