package com.thirdstone.commerce.platform.testing;

import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.CallerArgumentResolver;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Makes a MockMvc request look the way the edge forwards one for a signed-in user. */
public final class Edge {

    public static MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder request, long userId) {
        return as(request, userId, "USER", false);
    }

    public static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request, long userId) {
        return as(request, userId, "ADMIN", true);
    }

    public static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, String role,
                                                   boolean strong) {
        return request.header(CallerArgumentResolver.HEADER_EDGE_TOKEN, CommerceContainers.EDGE_SECRET)
                .header(Caller.HEADER_USER_ID, String.valueOf(userId))
                .header(Caller.HEADER_EMAIL, "user" + userId + "@example.com")
                .header(Caller.HEADER_ROLE, role)
                .header(Caller.HEADER_STRONG, String.valueOf(strong));
    }

    private Edge() {
    }
}
