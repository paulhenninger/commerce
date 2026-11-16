package com.thirdstone.commerce.platform.web;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves a {@link Caller} controller parameter from the edge's headers; 401 without them. */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
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
}
