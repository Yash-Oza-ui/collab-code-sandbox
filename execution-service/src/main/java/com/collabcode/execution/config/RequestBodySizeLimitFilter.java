
package com.collabcode.execution.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final ExecutionLimitsProperties limits;

    public RequestBodySizeLimitFilter(ExecutionLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/v1/runs".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        long maxBytes = limits.getMaxRequestBodyBytes();

        if (maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "max-request-body-bytes must be between 1 and "
                            + (Integer.MAX_VALUE - 1)
            );
        }

        byte[] body = request.getInputStream()
                .readNBytes((int) maxBytes + 1);

        if (body.length > maxBytes
                || request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":413,\"error\":\"Payload Too Large\","
                            + "\"message\":\"Request body exceeds the configured limit.\"}"
            );
            return;
        }

        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);

                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return input.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return input.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        try {
                            if (input.available() > 0) {
                                listener.onDataAvailable();
                            }
                            if (input.available() == 0) {
                                listener.onAllDataRead();
                            }
                        } catch (IOException e) {
                            listener.onError(e);
                        }
                    }
                };
            }
        };

        filterChain.doFilter(wrapped, response);
    }
}
