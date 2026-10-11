package io.github.marekpompura.db2mcp.testsupport;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Tier 1 test fixture (ADR 0009): an H2 instance in DB2 compatibility mode,
 * seeded from itsm-h2-schema.sql. Verifies the guard chain and JDBC round
 * trip for real, for the part of the tool set (executeQuery, lookupRefData)
 * that doesn't depend on Db2's actual system catalog.
 *
 * Each call creates a fresh named in-memory database so tests don't share
 * mutable state across test classes/methods.
 */
public final class ItsmH2Database {

    private ItsmH2Database() {}

    public static DataSource create(String uniqueName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + uniqueName + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("itsm-h2-schema.sql")).execute(dataSource);
        return dataSource;
    }
}
