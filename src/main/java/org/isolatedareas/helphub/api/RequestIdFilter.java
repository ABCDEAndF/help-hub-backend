package org.isolatedareas.helphub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}")) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            chain.doFilter(request, response);
        }
    }
}

