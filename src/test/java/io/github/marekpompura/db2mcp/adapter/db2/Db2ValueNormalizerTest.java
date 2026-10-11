package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the UTF-8-safe truncation boundary and type-dispatch
 * logic (CR-005, ADR 0023) that doesn't need a live JDBC connection -
 * {@link Db2QueryAdapterIntegrationTest}'s CLOB/BLOB/ARRAY cases cover the
 * real-driver-type dispatch end to end; this covers the byte-boundary math
 * precisely, including the case an H2-driven test can't easily target.
 */
class Db2ValueNormalizerTest {

    @Test
    void normalize_passesThroughNullUnchanged() {
        var result = Db2ValueNormalizer.normalize(null, 100);

        assertThat(result.value()).isNull();
        assertThat(result.approximateBytes()).isZero();
    }

    @Test
    void normalize_passesThroughBooleanAndNumberUnchanged() {
        assertThat(Db2ValueNormalizer.normalize(Boolean.TRUE, 100).value()).isEqualTo(Boolean.TRUE);
        assertThat(Db2ValueNormalizer.normalize(42, 100).value()).isEqualTo(42);
        assertThat(Db2ValueNormalizer.normalize(new java.math.BigDecimal("9999999999999.123456"), 100).value())
                .isEqualTo(new java.math.BigDecimal("9999999999999.123456"));
    }

    @Test
    void normalize_shortStringPassesThroughUnchanged() {
        var result = Db2ValueNormalizer.normalize("hello", 100);

        assertThat(result.value()).isEqualTo("hello");
        assertThat(result.approximateBytes()).isEqualTo(5);
    }

    @Test
    void normalize_oversizedAsciiStringIsTruncatedWithMarker() {
        var result = Db2ValueNormalizer.normalize("A".repeat(200), 50);

        String truncated = (String) result.value();
        assertThat(truncated).contains("[cell truncated: exceeds 50-byte limit]");
        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length).isEqualTo(result.approximateBytes());
    }

    // P0-2 (2026-09-01 master-brief gap review): the truncation suffix
    // previously counted for nothing against maxCellBytes - a cap of 50
    // could return up to 50 content bytes PLUS the ~40-byte marker,
    // silently exceeding the configured limit by the marker's own length.
    // The hard-cap guarantee now covers the whole rendered result.
    @Test
    void normalize_truncatedResult_neverExceedsMaxCellBytesEvenWithTheSuffix() {
        var result = Db2ValueNormalizer.normalize("A".repeat(200), 50);

        assertThat(result.approximateBytes()).isLessThanOrEqualTo(50);
        assertThat(((String) result.value()).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(50);
    }

    // Realistic-sized budget (this project's own default maxCellBytes),
    // where the suffix is negligible relative to the content - proves the
    // hard cap holds without materially changing behavior at real config
    // values, not only at the small budget the test above stresses.
    @Test
    void normalize_oversizedStringAtRealisticCellBudget_neverExceedsTheHardCap() {
        var result = Db2ValueNormalizer.normalize("A".repeat(20_000), 16_384);

        String truncated = (String) result.value();
        assertThat(truncated).contains("truncated");
        assertThat(result.approximateBytes()).isLessThanOrEqualTo(16_384);
        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length).isEqualTo(result.approximateBytes());
    }

    // Pathological edge case: a configured budget smaller than the marker
    // text itself. Still a hard cap, not a crash or a silently-ignored one.
    @Test
    void normalize_evenWhenBudgetIsSmallerThanTheSuffixItself_neverExceedsIt() {
        var result = Db2ValueNormalizer.normalize("A".repeat(200), 10);

        assertThat(result.approximateBytes()).isLessThanOrEqualTo(10);
        assertThat(((String) result.value()).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(10);
    }

    @Test
    void normalize_truncationNeverSplitsAMultiByteUtf8Character() {
        // Each "e" is a 2-byte UTF-8 character (U+00E9). A byte cap that lands
        // mid-character must back up to the previous character boundary, not
        // produce a mangled/invalid trailing byte sequence.
        String multiByte = "é".repeat(100); // 200 bytes of pure 2-byte characters
        var result = Db2ValueNormalizer.normalize(multiByte, 51); // odd cap: lands mid-character

        String truncated = (String) result.value();
        String beforeMarker = truncated.substring(0, truncated.indexOf(" [cell truncated"));
        byte[] reencoded = beforeMarker.getBytes(StandardCharsets.UTF_8);

        // Re-encoding what we kept must round-trip to a whole number of
        // 2-byte characters (even length) and must not contain the Unicode
        // replacement character, which a split multi-byte sequence would
        // produce on decode.
        assertThat(reencoded.length % 2).isEqualTo(0);
        assertThat(beforeMarker).doesNotContain("�");
        assertThat(reencoded.length).isLessThanOrEqualTo(51);
    }

    @Test
    void normalize_byteArrayIsOmittedNotMaterialized() {
        var result = Db2ValueNormalizer.normalize(new byte[1234], 100);

        assertThat(result.value()).isEqualTo("<binary value omitted, 1234 bytes>");
    }

    @Test
    void normalize_javaTimeValueBecomesIsoString() {
        var result = Db2ValueNormalizer.normalize(LocalDate.of(2026, 8, 15), 100);

        assertThat(result.value()).isEqualTo("2026-08-15");
    }

    @Test
    void normalize_unrecognizedTypeBecomesASafeMarker_notItsRawToString() {
        Object driverSpecific = new Object() {
            @Override
            public String toString() {
                return "jdbc:db2://internal-host:50000/SECRETDB user=admin";
            }
        };

        var result = Db2ValueNormalizer.normalize(driverSpecific, 100);

        assertThat((String) result.value()).doesNotContain("internal-host", "SECRETDB", "admin");
        assertThat((String) result.value()).startsWith("<unsupported value type:");
    }
}
