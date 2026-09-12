package com.puber.rider.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AD-54: every log line a request produces carries the caller's request id. AD-5: a surface the
 * gateway does not front mints its own when none arrives, so no request is ever untraceable.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestId.HEADER);
        String requestId = incoming == null || incoming.isBlank() ? RequestId.mint() : incoming;

        MDC.put(RequestId.MDC_KEY, requestId);
        response.setHeader(RequestId.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // A pooled request thread otherwise carries this id into the next request's logs,
            // which is worse than no id at all.
            MDC.remove(RequestId.MDC_KEY);
        }
    }
}
