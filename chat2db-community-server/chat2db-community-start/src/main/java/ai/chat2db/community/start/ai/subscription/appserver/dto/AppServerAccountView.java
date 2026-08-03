package ai.chat2db.community.start.ai.subscription.appserver.dto;

/**
 * Non-secret account snapshot. Never includes tokens or auth payloads.
 */
public final class AppServerAccountView {

    private final boolean authenticated;
    private final String accountType;
    private final String maskedEmail;
    private final String planType;

    public AppServerAccountView(
            boolean authenticated,
            String accountType,
            String maskedEmail,
            String planType) {
        this.authenticated = authenticated;
        this.accountType = accountType;
        this.maskedEmail = maskedEmail;
        this.planType = planType;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public String accountType() {
        return accountType;
    }

    public String maskedEmail() {
        return maskedEmail;
    }

    public String planType() {
        return planType;
    }
}
