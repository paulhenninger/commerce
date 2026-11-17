package com.thirdstone.commerce.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final CallerArgumentResolver resolver = new CallerArgumentResolver(SECRET);

    @Test
    void headersFromTheEdgeBecomeTheCaller() {
        MockHttpServletRequest request = request(SECRET);
        Caller caller = resolve(resolver, request);
        assertThat(caller).isEqualTo(new Caller(42, "a@example.com", "ADMIN", true));
        assertThat(caller.isAdmin()).isTrue();
    }

    @Test
    void forgedHeadersWithoutTheEdgeTokenAreNotBelieved() {
        assertUnauthorized(resolver, request(null));
    }

    @Test
    void forgedHeadersWithAWrongEdgeTokenAreNotBelieved() {
        assertUnauthorized(resolver, request("0123456789abcdef0123456789abcdeX"));
        assertUnauthorized(resolver, request(""));
        assertUnauthorized(resolver, request(SECRET + "x"));
    }

    @Test
    void aServiceWithNoSecretBelievesNobody() {
        assertUnauthorized(new CallerArgumentResolver(""), request(""));
        assertUnauthorized(new CallerArgumentResolver(null), request(SECRET));
    }

    @Test
    void edgeRequestWithoutAUserIsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CallerArgumentResolver.HEADER_EDGE_TOKEN, SECRET);
        assertUnauthorized(resolver, request);
        request.addHeader(Caller.HEADER_USER_ID, "1 OR 1=1");
        assertUnauthorized(resolver, request);
    }

    @Test
    void onlyAdministratorsPassRequireAdmin() {
        assertThatThrownBy(() -> new Caller(1, "u@example.com", "USER", true).requireAdmin())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(Caller.HEADER_USER_ID, "42");
        request.addHeader(Caller.HEADER_EMAIL, "a@example.com");
        request.addHeader(Caller.HEADER_ROLE, "ADMIN");
        request.addHeader(Caller.HEADER_STRONG, "true");
        if (token != null) {
            request.addHeader(CallerArgumentResolver.HEADER_EDGE_TOKEN, token);
        }
        return request;
    }

    private static Caller resolve(CallerArgumentResolver resolver, MockHttpServletRequest request) {
        return (Caller) resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    private static void assertUnauthorized(CallerArgumentResolver resolver, MockHttpServletRequest request) {
        assertThatThrownBy(() -> resolve(resolver, request)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
