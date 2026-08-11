package io.janus.applications;

/**
 * The only two responses that ever carry an API key: registering an identity, and rotating its key.
 *
 * @param apiKey shown once and never recoverable; Janus keeps only a hash of it
 */
public record IssuedApplication(ApplicationResponse application, String apiKey) {}
