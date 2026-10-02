package com.arthadhruva.riskengine.assistant;

import java.util.regex.Pattern;

/**
 * Masks personal identifiers before text leaves for a third-party model provider. The structured loan
 * fields carry none, but analysts' notes are free text: someone will eventually paste a borrower's
 * phone number or email into one. Pattern-based, so it is a net for the common shapes (email, US social
 * security number, phone number, account-length digit runs), not a guarantee.
 */
final class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.])(?:\\+?\\d{1,3}[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]\\d{3}[ .-]\\d{4}(?![\\w.])");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\w.])\\d{9,19}(?![\\w.])");

    private PiiRedactor() {
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = SSN.matcher(out).replaceAll("[ssn]");
        out = PHONE.matcher(out).replaceAll("[phone]");
        return LONG_NUMBER.matcher(out).replaceAll("[number]");
    }
}
