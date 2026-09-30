package com.dcos.platform.certapi.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

    private CorrelationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationFilter();
        MDC.clear();
    }

    @Test
    void preservesSuppliedCorrelationHeader() throws Exception {
        String suppliedId = "supplied-correlation-id";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-correlation-id", suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(suppliedId, response.getHeader("x-correlation-id"));
    }

    @Test
    void generatesCorrelationIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String correlationId = response.getHeader("x-correlation-id");
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());
    }

    @Test
    void generatesCorrelationIdWhenBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-correlation-id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String correlationId = response.getHeader("x-correlation-id");
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());
    }

    @Test
    void populatesLoggingContextDuringHandling() throws Exception {
        String suppliedId = "test-correlation-id";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-correlation-id", suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Capture MDC value during handling
        AtomicReference<String> capturedCorrelationId = new AtomicReference<>();
        jakarta.servlet.FilterChain chain = Mockito.mock(jakarta.servlet.FilterChain.class);
        Mockito.doAnswer(
                        invocation -> {
                            capturedCorrelationId.set(MDC.get(LoggingContext.CORRELATION_ID));
                            return null;
                        })
                .when(chain)
                .doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertEquals(
                suppliedId,
                capturedCorrelationId.get(),
                "MDC should contain correlation ID during request handling");
    }

    @Test
    void clearsLoggingContextAfterHandling() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-correlation-id", "test-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(MDC.get(LoggingContext.CORRELATION_ID), "MDC should be cleared after request");
    }

    @Test
    void clearsLoggingContextEvenWhenHandlerThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-correlation-id", "test-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        jakarta.servlet.FilterChain chain = Mockito.mock(jakarta.servlet.FilterChain.class);
        Mockito.doThrow(new RuntimeException("Test exception"))
                .when(chain)
                .doFilter(request, response);

        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));
        assertNull(
                MDC.get(LoggingContext.CORRELATION_ID),
                "MDC should be cleared even after exception");
    }
}
