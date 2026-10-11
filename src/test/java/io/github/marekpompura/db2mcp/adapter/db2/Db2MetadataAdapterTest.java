package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Db2MetadataAdapterTest {

    @Test
    void mapSchema_trimsFixedWidthCharColumn() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("SCHEMANAME")).thenReturn("ITSM" + " ".repeat(124));

        SchemaInfo result = Db2MetadataAdapter.mapSchema(rs, 0);

        assertThat(result.schemaName()).isEqualTo("ITSM");
    }

    @ParameterizedTest
    @CsvSource({"T,TABLE", "V,VIEW"})
    void mapTable_translatesTypeCodeToReadableType(String typeCode, String expected) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("TABSCHEMA")).thenReturn("ITSM");
        when(rs.getString("TABNAME")).thenReturn("TICKET");
        when(rs.getString("TYPE")).thenReturn(typeCode);

        TableInfo result = Db2MetadataAdapter.mapTable(rs, 0);

        assertThat(result.tableType()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"Y,true", "N,false"})
    void mapColumn_mapsPrimaryKeyFlag(String isPk, boolean expectedPrimaryKey) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("COLNAME")).thenReturn("TICKET_ID");
        when(rs.getString("TYPENAME")).thenReturn("INTEGER");
        when(rs.getObject("LENGTH")).thenReturn(4);
        when(rs.getObject("SCALE")).thenReturn(0);
        when(rs.getString("NULLS")).thenReturn("N");
        when(rs.getInt("COLNO")).thenReturn(0);
        when(rs.getString("IS_PK")).thenReturn(isPk);

        ColumnInfo result = Db2MetadataAdapter.mapColumn(rs, 0);

        assertThat(result.primaryKey()).isEqualTo(expectedPrimaryKey);
        assertThat(result.nullable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ITSM", "TICKET_COMMENT", "COL#1", "COL$1", "COL_1", "COL@1"})
    void requireIdentifier_acceptsAllowlistedCharacters(String identifier) {
        Db2MetadataAdapter.requireIdentifier(identifier);
        // no exception = pass
    }

    @ParameterizedTest
    @ValueSource(strings = {"ITSM; DROP TABLE X", "ITSM'", "ITSM--", "ITSM.TABLE", "a b", ""})
    void requireIdentifier_rejectsAnythingOutsideAllowlist(String identifier) {
        assertThatThrownBy(() -> Db2MetadataAdapter.requireIdentifier(identifier))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireIdentifier_rejectsNull() {
        assertThatThrownBy(() -> Db2MetadataAdapter.requireIdentifier(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
