package ai.chat2db.plugin.oracle.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for plugin:oracle-6: the index metadata query must match the index owner
 * in its joins. Index names are only unique per index owner (schema), so joining
 * all_ind_columns/all_ind_expressions to all_indexes on (table_owner, table_name, index_name)
 * alone cross-joins same-named indexes owned by different schemas and mixes their
 * columns/uniqueness/type into one TableIndex.
 */
class OracleMetaDataConstantsTest {

    @Test
    void selectTableIndexJoinsAllIndexesOnIndexOwner() {
        assertTrue(OracleMetaDataConstants.SELECT_TABLE_INDEX.contains("aic.index_owner = ai.owner"),
                "all_ind_columns must join all_indexes on index owner to avoid cross-schema index collisions");
    }

    @Test
    void selectTableIndexJoinsAllIndExpressionsOnIndexOwner() {
        assertTrue(OracleMetaDataConstants.SELECT_TABLE_INDEX.contains("aic.index_owner = ex.index_owner"),
                "all_ind_columns must join ALL_IND_EXPRESSIONS on index owner to avoid cross-schema index collisions");
    }

    @Test
    void selectTableIndexJoinsExpressionsOnColumnPosition() {
        assertTrue(OracleMetaDataConstants.SELECT_TABLE_INDEX.contains(
                        "aic.COLUMN_POSITION = ex.COLUMN_POSITION"),
                "each function-based index column must join only its expression at the same position");
    }
}
