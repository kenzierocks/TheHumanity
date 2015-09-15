package org.royaldev.thehumanity.ping;

public enum PingRegistrationOption {

    RECEIVE("If enabled, messages about new games will be sent to you."),
    PING_ALL_AUTHED_NICKS("If enabled, all authed nicknames for the account will be pinged. If not, only the first one encountered will be.");

    private final String description;

    PingRegistrationOption(final String description) {
        this.description = description;
    }

    public String getDescription() {
        return this.description;
    }
}
