package io.github.marekpompura.db2mcp.domain.model;

/**
 * How an adapter's connected database treats unquoted vs. quoted
 * identifiers (database-portability extension section 5.1). For DB2 LUW:
 * unquoted identifiers are folded to uppercase and stored that way (already
 * relied on throughout {@code adapter.db2} - see {@code Db2MetadataAdapter}'s
 * {@code IDENTIFIER} pattern and {@code fingerprintOf}'s uppercasing);
 * double-quoted identifiers preserve case exactly as written.
 */
public record IdentifierRules(char quoteChar, boolean unquotedIdentifiersFoldedToUpperCase) {
}
