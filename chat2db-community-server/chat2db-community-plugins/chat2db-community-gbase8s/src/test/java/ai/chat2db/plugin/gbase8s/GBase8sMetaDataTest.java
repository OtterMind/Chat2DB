package ai.chat2db.plugin.gbase8s;

import ai.chat2db.plugin.gbase8s.builder.GBase8sSqlBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GBase8sMetaDataTest {

    private final GBase8sMetaData metaData = new GBase8sMetaData();

    @Test
    void registersGBase8sSqlBuilder() {
        assertInstanceOf(GBase8sSqlBuilder.class, metaData.getSqlBuilder());
    }

    @Test
    void databaseAndTableUseColonForTwoOrThreeSlots() {
        // GBase 8s (Informix lineage) parses 'mydb.t' as owner.table in the current database,
        // so a database-qualified reference must use the Informix-style colon separator.
        assertEquals("mydb:t", metaData.getMetaDataName("mydb", "t"));
        assertEquals("mydb:t", metaData.getMetaDataName("mydb", "", "t"));
        assertEquals("mydb:t", metaData.getMetaDataName("mydb", null, "t"));
    }

    @Test
    void ownerAndTableUseDotWhenDatabaseIsMissing() {
        assertEquals("gbasedbt.t", metaData.getMetaDataName("", "gbasedbt", "t"));
        assertEquals("gbasedbt.t", metaData.getMetaDataName(null, "gbasedbt", "t"));
    }

    @Test
    void databaseOwnerAndTableUseColonThenDot() {
        assertEquals("mydb:gbasedbt.t", metaData.getMetaDataName("mydb", "gbasedbt", "t"));
    }

    @Test
    void firstDatabaseAndLastOwnerTableAreUsedWhenExtraSlotsArePresent() {
        assertEquals("mydb:gbasedbt.t", metaData.getMetaDataName("mydb", "ignored", "gbasedbt", "t"));
    }

    @Test
    void identifierTextDoesNotAffectSeparatorSelection() {
        assertEquals("my.db:owner:name.table.part",
                metaData.getMetaDataName("my.db", "owner:name", "table.part"));
        assertEquals("my.db:table.part", metaData.getMetaDataName("my.db", "", "table.part"));
    }

    @Test
    void blankNamesAreOmittedAfterSlotsAreResolved() {
        assertEquals("t", metaData.getMetaDataName("t"));
        assertEquals("t", metaData.getMetaDataName("", "", "t"));
        assertEquals("t", metaData.getMetaDataName(null, null, "t"));
        assertEquals("", metaData.getMetaDataName());
        assertEquals("", metaData.getMetaDataName("", null, ""));
    }
}
