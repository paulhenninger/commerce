package com.thirdstone.commerce.platform.web;

/**
 * Who is calling, as established by the edge.
 *
 * <p>The browser's access token never reaches a domain service. The edge (nginx)
 * asks the identity service to check it on every request — signature, session still
 * live, account not locked — and, only if that passes, forwards the request with
 * these facts in {@code X-Auth-*} headers, having first stripped any the client
 * sent. Domain services sit on networks only the edge can reach, so the headers are
 * as trustworthy as the edge itself, and no service but identity holds the signing
 * key. Take it as a controller parameter: {@code Caller me}.
 */
public record Caller(long userId, String email, String role, boolean strongAuth) {

    public static final String HEADER_USER_ID = "X-Auth-User-Id";
    public static final String HEADER_EMAIL = "X-Auth-Email";
    public static final String HEADER_ROLE = "X-Auth-Role";
    public static final String HEADER_STRONG = "X-Auth-Strong";

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /** For admin endpoints: anyone else gets 403. */
    public Caller requireAdmin() {
        if (!isAdmin()) {
            throw ApiException.forbidden("Administrators only");
        }
        return this;
    }
}
