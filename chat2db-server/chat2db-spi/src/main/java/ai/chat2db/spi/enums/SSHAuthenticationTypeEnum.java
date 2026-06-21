package ai.chat2db.spi.enums;

import ai.chat2db.server.tools.base.enums.BaseEnum;

public enum SSHAuthenticationTypeEnum implements BaseEnum<String> {
    /**
     * 密钥文件
     */
    KEYFILE("密钥文件"),
    /**
     * 密码
     */
    PASSWORD("密码"),
    /**
     * 未知
     */
    UNKNOWN("未知"),

    ;

    final String description;

    SSHAuthenticationTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

    @Override
    public String getDescription() {
        return description;
    }
}
