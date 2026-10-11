package io.github.marekpompura.db2mcp.adapter.db2;

import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.temporal.TemporalAccessor;

/**
 * Normalizes a raw JDBC {@code ResultSet.getObject()} value into a
 * JSON-safe representation and bounds any single cell to {@code
 * maxCellBytes} (CR-005, 2026-08-14 code review, ADR 0023). Without this,
 * a single row containing a large CLOB/BLOB/XML value could still exhaust
 * memory, MCP payload size, or an LLM's context window even under {@code
 * executeQuery}'s row cap, and an unrecognized driver-specific type's raw
 * {@code toString()} could leak internal detail the rest of this project's
 * error-sanitization work (ADR 0018, ADR 0020) was built to avoid.
 *
 * <p>Types verified against a real H2 connection before being trusted, not
 * assumed from the JDBC spec alone: {@code CAST(... AS CLOB)} returns
 * {@code org.h2.jdbc.JdbcClob} (implements {@link Clob}); {@code CAST(...
 * AS BLOB)} returns {@code org.h2.jdbc.JdbcBlob} (implements {@link Blob});
 * a {@code TIMESTAMP} column returns {@link java.time.OffsetDateTime}, not
 * {@link java.sql.Timestamp}. DB2's real driver may return different
 * concrete types for the same SQL types - Tier 2 (ADR 0009) for anything
 * not exercised here.
 */
final class Db2ValueNormalizer {

    private static final String BLOB_OMITTED = "<binary value omitted, %d bytes>";
    private static final String UNSUPPORTED = "<unsupported value type: %s>";
    private static final String TRUNCATED_SUFFIX = " [cell truncated: exceeds %d-byte limit]";
    private static final String UNREADABLE = "<value unreadable>";

    private Db2ValueNormalizer() {}

    record Normalized(Object value, int approximateBytes) {}

    static Normalized normalize(Object raw, int maxCellBytes) {
        try {
            return doNormalize(raw, maxCellBytes);
        } catch (SQLException e) {
            // A driver error reading a Clob/Blob/SQLXML's own content, not a
            // query-execution failure - sanitized the same as everywhere else
            // in this project rather than letting a raw driver message escape.
            return new Normalized(UNREADABLE, UNREADABLE.length());
        }
    }

    private static Normalized doNormalize(Object raw, int maxCellBytes) throws SQLException {
        if (raw == null) {
            return new Normalized(null, 0);
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            // Fixed-size types with no meaningful "oversized cell" risk - a
            // rough JSON-rendering estimate, not an exact serialized length.
            return new Normalized(raw, 8);
        }
        if (raw instanceof String s) {
            return boundedString(s, maxCellBytes);
        }
        if (raw instanceof Clob clob) {
            return boundedClob(clob, maxCellBytes);
        }
        if (raw instanceof Blob blob) {
            return bound(BLOB_OMITTED.formatted(blob.length()), maxCellBytes, false);
        }
        if (raw instanceof byte[] bytes) {
            return bound(BLOB_OMITTED.formatted(bytes.length), maxCellBytes, false);
        }
        if (raw instanceof SQLXML xml) {
            return boundedString(xml.getString(), maxCellBytes);
        }
        if (raw instanceof java.sql.Date
                || raw instanceof java.sql.Time
                || raw instanceof java.sql.Timestamp
                || raw instanceof TemporalAccessor) {
            // java.sql.Date/Time/Timestamp and every java.time.* value type
            // (Instant, LocalDate, OffsetDateTime, ...) have a safe,
            // documented, ISO-8601-shaped toString() - no driver internals.
            return boundedString(String.valueOf(raw), maxCellBytes);
        }
        // Anything else is an unrecognized driver-specific type. Its
        // toString() is not trusted by default - a specific driver's object
        // could embed connection or internal detail an ordinary JDK/java.time
        // type never would.
        return bound(UNSUPPORTED.formatted(raw.getClass().getSimpleName()), maxCellBytes, false);
    }

    private static Normalized boundedClob(Clob clob, int maxCellBytes) throws SQLException {
        long length = clob.length();
        // Read one character beyond the theoretical worst case, not exactly
        // maxCellBytes characters (P0-2, 2026-09-01 review): every UTF-8
        // character is at least 1 byte, so maxCellBytes+1 characters is
        // always enough to know both whether the byte budget is exceeded and
        // whether unread CLOB content remains beyond what was fetched - the
        // same "+1 to detect truncation without a second query" pattern
        // Db2QueryAdapter's own row cap already uses, applied here so a
        // multi-byte-heavy CLOB can't silently read as "fits" just because
        // its character count was within budget.
        int readBudget = (int) Math.min(length, (long) maxCellBytes + 1);
        String prefix = clob.getSubString(1, Math.max(readBudget, 0));
        boolean moreContentBeyondPrefix = length > readBudget;
        return bound(prefix, maxCellBytes, moreContentBeyondPrefix);
    }

    private static Normalized boundedString(String value, int maxCellBytes) {
        return bound(value, maxCellBytes, false);
    }

    /**
     * The single hard-cap primitive every cell value goes through (P0-2,
     * 2026-09-01 master-brief gap review): {@code approximateBytes} on the
     * returned {@link Normalized} is a true hard bound on {@code
     * maxCellBytes}, not an estimate that the appended {@link
     * #TRUNCATED_SUFFIX} could still push over it. The suffix is truncated
     * into the same budget it announces, not appended on top of it -
     * {@code moreContentExists} lets a caller that only fetched a bounded
     * prefix (see {@link #boundedClob}) still trigger truncation even when
     * that prefix alone happens to fit.
     */
    private static Normalized bound(String content, int maxCellBytes, boolean moreContentExists) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (!moreContentExists && bytes.length <= maxCellBytes) {
            return new Normalized(content, bytes.length);
        }
        String suffix = TRUNCATED_SUFFIX.formatted(maxCellBytes);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        int contentBudget = Math.max(0, maxCellBytes - suffixBytes.length);
        int contentEnd = utf8SafeLength(bytes, contentBudget);
        String candidate = new String(bytes, 0, contentEnd, StandardCharsets.UTF_8) + suffix;
        byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
        if (candidateBytes.length <= maxCellBytes) {
            return new Normalized(candidate, candidateBytes.length);
        }
        // Pathological only: maxCellBytes configured smaller than the
        // suffix text itself. Still a hard cap - truncate the fully
        // rendered result (suffix included), the same UTF-8-safe way.
        int finalEnd = utf8SafeLength(candidateBytes, maxCellBytes);
        String finalValue = new String(candidateBytes, 0, finalEnd, StandardCharsets.UTF_8);
        return new Normalized(finalValue, finalValue.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Walks back from {@code budget} to the start of a UTF-8 sequence (a
     * continuation byte matches {@code 10xxxxxx}) so truncation never splits
     * a multi-byte character and produces invalid UTF-8.
     */
    private static int utf8SafeLength(byte[] bytes, int budget) {
        int end = Math.max(0, Math.min(budget, bytes.length));
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return end;
    }
}
