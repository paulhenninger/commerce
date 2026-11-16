package com.thirdstone.commerce.platform.web;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Resolves a {@link Caller} controller parameter from the edge's headers; 401 without them.
 *
 * <p>The {@code X-Auth-*} headers are believed only on a request that also carries
 * the secret the edges share with the services ({@code X-Edge-Token}). Network
 * isolation is the first defence — only the edges can reach a service's port — and
 * this is the second: a container that somehow does reach it still cannot claim to
 * be a user, because it does not know the token.
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER_EDGE_TOKEN = "X-Edge-Token";

    private final byte[] edgeSecret;

    /** @param edgeSecret the shared secret; blank means no request is ever believed */
    public CallerArgumentResolver(String edgeSecret) {
        this.edgeSecret = edgeSecret == null ? new byte[0] : edgeSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        if (!fromEdge(request.getHeader(HEADER_EDGE_TOKEN))) {
            throw ApiException.unauthorized();
        }
        String id = request.getHeader(Caller.HEADER_USER_ID);
        if (id == null || id.isBlank()) {
            throw ApiException.unauthorized();
        }
        long userId;
        try {
            userId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw ApiException.unauthorized();
        }
        MDC.put("userId", String.valueOf(userId));
        String role = request.getHeader(Caller.HEADER_ROLE);
        return new Caller(userId, request.getHeader(Caller.HEADER_EMAIL),
                role == null ? "USER" : role, "true".equals(request.getHeader(Caller.HEADER_STRONG)));
    }

    private boolean fromEdge(String token) {
        if (edgeSecret.length == 0 || token == null) {
            return false;
        }
        // Constant-time: a byte-by-byte equals would leak, through response timing,
        // how much of a guessed token is right.
        return MessageDigest.isEqual(edgeSecret, token.getBytes(StandardCharsets.UTF_8));
    }
}
