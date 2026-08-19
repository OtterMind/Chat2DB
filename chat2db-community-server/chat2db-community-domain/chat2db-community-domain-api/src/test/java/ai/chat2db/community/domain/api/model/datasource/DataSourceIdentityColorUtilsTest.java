package ai.chat2db.community.domain.api.model.datasource;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataSourceIdentityColorUtilsTest {

    @Test
    void normalizesTrimmedHexToUpperCase() {
        assertEquals("#A1B2C3", DataSourceIdentityColorUtils.normalize("  #a1b2c3  "));
        assertNull(DataSourceIdentityColorUtils.normalize(null));
    }

    @Test
    void rejectsValuesOutsideSixDigitHexFormat() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> DataSourceIdentityColorUtils.normalize("#ABC"));

        assertEquals("datasource.identityColor.invalid", exception.getCode());
    }
}
