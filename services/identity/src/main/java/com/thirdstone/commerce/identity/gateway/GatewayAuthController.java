package com.thirdstone.commerce.identity.gateway;

import com.thirdstone.commerce.identity.common.ApiException;
import com.thirdstone.commerce.identity.config.AppProperties;
import com.thirdstone.commerce.identity.session.AuthPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The edge's question, asked before it forwards any authenticated request to
 * another service: "who is this, and may they?"
 *
 * <p>Only this service holds the token signing key, and only it knows whether a
 * session is still live. So the other services never see the access token: the
 * edge (nginx {@code auth_request}) sends it here, and on 204 copies the
 * {@code X-Auth-*} response headers onto the request it forwards. The filter chain
 * has already done the work by the time this method runs — signature, session not
 * revoked, account not locked, re-read from the database on every call — so a
 * sign-out or a lock takes effect on the very next request to any service.
 *
 * <p>The path is outside {@code /api}, so no edge routes it to the internet, and
 * the edges refuse {@code /internal} outright.
 */
@RestController
public class GatewayAuthController {

    static final String USER_ID = "X-Auth-User-Id";
    static final String EMAIL = "X-Auth-Email";
    static final String ROLE = "X-Auth-Role";
    static final String STRONG = "X-Auth-Strong";
    /** Why a request was refused, for the edge to put in its JSON error body. */
    static final String ERROR = "X-Auth-Error";

    private final boolean opsRequiresMfa;

    public GatewayAuthController(AppProperties props) {
        this.opsRequiresMfa = props.security().opsRequiresMfa();
    }

    /**
     * @param require {@code admin} for back-office and operations routes: the caller
     *                must be an administrator and, unless switched off, signed in
     *                with a second factor
     */
    @GetMapping("/internal/authz")
    public ResponseEntity<Void> authz(@AuthenticationPrincipal AuthPrincipal me,
                                      @RequestParam(required = false) String require,
                                      HttpServletResponse response) {
        if (require != null) {
            if (!"admin".equals(require)) {
                // An edge asking for a requirement this version does not know must
                // fail closed, not be waved through as "no requirement".
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            if (!me.isAdmin()) {
                response.setHeader(ERROR, "FORBIDDEN");
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            if (opsRequiresMfa && !me.strongAuth()) {
                response.setHeader(ERROR, ApiException.ADMIN_MFA_REQUIRED);
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Administrators must sign in with two-factor authentication or a passkey.",
                        ApiException.ADMIN_MFA_REQUIRED);
            }
        }
        return ResponseEntity.noContent()
                .header(USER_ID, String.valueOf(me.userId()))
                .header(EMAIL, me.email())
                .header(ROLE, me.role().name())
                .header(STRONG, String.valueOf(me.strongAuth()))
                .build();
    }
}
