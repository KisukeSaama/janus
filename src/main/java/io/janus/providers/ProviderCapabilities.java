package io.janus.providers;

/**
 * What this deployment permits a destination to be, so the console does not offer a setting the
 * backend would refuse. Fixed at startup: every field here comes from configuration, not from data.
 *
 * @param privateDestinations whether a destination may be registered as living on the local network
 */
public record ProviderCapabilities(boolean privateDestinations) {}
