package org.isolatedareas.helphub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """, Long.class);
    private final StringRedisTemplate redis;
    private final int limit;

    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${app.rate-limit.requests-per-minute}") int limit) {
        this.redis = redis;
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/swagger-ui/") || path.startsWith("/v3/api-docs/")
            || path.startsWith("/api/payments/wechat/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String forwarded = request.getHeader("X-Forwarded-For");
        String client = forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        long window = System.currentTimeMillis() / 60_000L;
        String key = "rate:" + client + ":" + window;
        Long value = redis.execute(INCREMENT, Collections.singletonList(key), "120");
        long count = value == null ? 1L : value;
        response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, limit - count)));
        if (count > limit) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
