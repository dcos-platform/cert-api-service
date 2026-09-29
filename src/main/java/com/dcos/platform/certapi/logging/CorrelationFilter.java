package com.dcos.platform.certapi.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that establishes a correlation ID at the earliest point in request handling,
 * populates the logging context (MDC), and echoes it on the response. Clears the context in a
 * finally block to prevent leaks across pooled threads.
 */
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId =
                LoggingContext.resolveCorrelationId(
                        request.getHeader(LoggingContext.CORRELATION_HEADER));

        try {
            MDC.put(LoggingContext.CORRELATION_ID, correlationId);
            response.setHeader(LoggingContext.CORRELATION_HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
