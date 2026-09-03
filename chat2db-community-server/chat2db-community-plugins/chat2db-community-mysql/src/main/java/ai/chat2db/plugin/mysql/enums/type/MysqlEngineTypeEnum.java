package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.EngineType;

import java.util.Arrays;
import java.util.List;

public enum MysqlEngineTypeEnum {

    INNODB("InnoDB"),
    MYISAM("MyISAM"),
    MEMORY("MEMORY"),
    CSV("CSV"),
    ARCHIVE("ARCHIVE"),
    BLACKHOLE("BLACKHOLE"),
    FEDERATED("FEDERATED"),
    MRG_MYISAM("MRG_MYISAM"),
    NDB("NDB");

    private final EngineType engineType;

    MysqlEngineTypeEnum(String name) {
        this.engineType = new EngineType(name, false, false, false, false, false, false, false, false);
    }

    public EngineType getEngineType() {
        return engineType;
    }

    public static List<EngineType> getEngineTypes() {
        return Arrays.stream(MysqlEngineTypeEnum.values())
                .map(MysqlEngineTypeEnum::getEngineType)
                .toList();
    }
}
