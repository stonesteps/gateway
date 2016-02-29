package com.tritonsvc.messageprocessor.util;

/**
 * Helps with number operations.
 */
public final class NumberHelper {

    private NumberHelper() {
        // utility
    }

    public static boolean isDouble(final String string) {
        boolean isDouble = false;
        try {
            Double.parseDouble(string);
            isDouble = true;
        } catch (final NumberFormatException e) {
            // ignore
        }
        return isDouble;
    }
}