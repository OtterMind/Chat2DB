package ai.chat2db.community.jcef.enums;

public enum UpdatedStatus {
    Default("default"),
    Available("available"),
    NotAvailable("notAvailable"),
    Updating("updating"),
    Updated("updated"),
    Installed("installed"),
    UpdateFailed("updateFailed");

    private final String name;

    UpdatedStatus(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
    public static UpdatedStatus fromValue(String value) {
        for (UpdatedStatus status : values()) {
            if (status.name.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid status value: " + value);
    }
}
