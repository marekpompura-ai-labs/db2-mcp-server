package io.github.marekpompura.db2mcp.testsupport;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The full ITSM demo dataset (docs/demo/itsm-schema.sql, minus the stored
 * procedure H2 can't run) in H2/DB2-compatibility mode, used only to verify
 * the Tier 1 guided scenarios in docs/demo/guided-scenarios.md actually
 * produce what that document claims (ADR 0009). Distinct from
 * {@link ItsmH2Database}, which serves the general automated test suite.
 */
public final class ItsmDemoH2Database {

    private ItsmDemoH2Database() {}

    public static DataSource create(String uniqueName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + uniqueName + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("itsm-demo-h2-schema.sql")).execute(dataSource);
        return dataSource;
    }
}
