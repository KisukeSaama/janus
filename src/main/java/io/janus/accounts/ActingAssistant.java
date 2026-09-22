package io.janus.accounts;

import java.util.UUID;

/**
 * The AI assistant a request arrived through, when it arrived through one.
 *
 * <p>Carried as the authentication's details beside the account it acts for. The account is who is
 * answerable and whose role applies; this is only how it reached Janus, recorded so the journal can
 * tell a change somebody made from one their assistant made on their behalf.
 *
 * @param clientName what the assistant called itself when it registered: descriptive, never proof
 */
public record ActingAssistant(UUID connectionId, String clientName) {}
