package mcp;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Authorization handling of a single MCP server, bound to that server by
 * {@link McpAuthorizationProvider#resolveAuthorization}.
 */
public interface McpServerAuthorization {

    /**
     * Writes the credential of the moment into an outgoing request.
     *
     * <p>Called once per HTTP request of the transport, so it must read the credential instead of
     * capturing a value — that is what lets a token renewed between two requests be picked up
     * without rebuilding the transport. The builder it receives is a per-request copy of the
     * configured one, so a header written here replaces a configured header of the same name; the
     * library applies this hook after copying that template, and that order is what lets a runtime
     * credential win over a stale configured value.</p>
     *
     * @param requestBuilder builder of the request about to be sent, never null
     */
    void customize(HttpRequest.Builder requestBuilder);

    /**
     * Records that the server rejected a request with 401 or 403.
     *
     * <p>Called by the transport right before it fails the request. Renewing a credential here is
     * allowed and cheap — the library runs this on its bounded elastic scheduler — but nothing that
     * waits for a person belongs in this method: its duration is charged to the caller's request.
     * A user-driven sign-in therefore has to be started by the caller and picked up by the next
     * connection, which asks {@link #customize} for the credential of the moment.</p>
     *
     * @param response the rejection; its headers carry the challenge that names the authority to
     *                 authenticate against
     */
    void onAuthorizationError(HttpResponse.ResponseInfo response);
}
