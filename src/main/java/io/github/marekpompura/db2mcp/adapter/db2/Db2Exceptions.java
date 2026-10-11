package io.github.marekpompura.db2mcp.adapter.db2;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/**
 * Shared by every outbound Db2 adapter (CR-002, master brief extension
 * section 10) so {@code Db2QueryAdapter} and {@code Db2MetadataAdapter}
 * unwrap a {@link DataAccessException}'s SQLSTATE the same way instead of
 * duplicating the cause-unwrapping logic. Deliberately not in
 * {@code domain.error}: that package must stay framework-free (ArchUnit's
 * {@code domain_layer_must_not_depend_on_any_other_layer}, and the
 * portability extension's "domain must not depend on ... a database
 * vendor"), so Spring's {@code DataAccessException} type stays adapter-side.
 */
final class Db2Exceptions {

    private Db2Exceptions() {}

    static String extractSqlState(DataAccessException e) {
        Throwable cause = e.getCause();
        return cause instanceof SQLException sqlException ? sqlException.getSQLState() : null;
    }
}
