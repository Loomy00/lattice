package org.accessformac.ui;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/** Converts explicit editor input to JDBC values without interpolating it into SQL. */
final class ValueParser {
    private ValueParser() {}

    static Object parse(String type, String input, boolean nullable) {
        String kind = type.toUpperCase(Locale.ROOT);
        if (kind.contains("TEXT") || kind.contains("MEMO") || kind.contains("CHAR") || kind.contains("GUID")) return input;
        String value = input.trim();
        if (value.isEmpty() && nullable) return null;
        try {
            if (kind.contains("BOOL") || kind.equals("YESNO") || kind.equals("BIT")) {
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "1", "-1" -> true;
                    case "false", "no", "0" -> false;
                    default -> throw new IllegalArgumentException("Enter Yes/No or True/False.");
                };
            }
            if (kind.contains("DATE") || kind.contains("TIME")) {
                if (value.length() == 10) return Timestamp.valueOf(LocalDate.parse(value).atStartOfDay());
                return Timestamp.valueOf(LocalDateTime.parse(value.replace(' ', 'T')));
            }
            if (kind.contains("BYTE") || kind.equals("INT") || kind.contains("INTEGER") || kind.equals("LONG") || kind.equals("SHORT") || kind.contains("COUNTER") || kind.contains("AUTOINCREMENT")) return Long.valueOf(value);
            if (kind.contains("MONEY") || kind.contains("CURRENCY") || kind.contains("DECIMAL") || kind.contains("NUMERIC") || kind.contains("DOUBLE") || kind.contains("FLOAT") || kind.contains("SINGLE") || kind.equals("REAL")) return new BigDecimal(value);
            return input;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid " + type + " value: " + input + ". Dates use YYYY-MM-DD or YYYY-MM-DD HH:MM:SS; numbers use a decimal point.", invalid);
        }
    }

    static String identifier(String name) { return "[" + name.replace("]", "]]") + "]"; }

    static String literal(String type, String input) {
        Object value = parse(type, input, false);
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        if (value instanceof Timestamp stamp) return "#" + stamp.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")) + "#";
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
