package com.tengencorp.tengen.security;

import com.tengencorp.tengen.exception.RequestBodyLimitExceededException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Enforces the ingestion limit even for chunked requests without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public RequestBodyLimitFilter(
            @Value("${tengen.ingestion.max-body-bytes:1048576}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/events".equals(request.getServletPath()) || !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, IOException {
        filterChain.doFilter(new LimitedRequest(request, maxBodyBytes), response);
    }

    private static class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0) increment(1);
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int read = delegate.read(bytes, offset, length);
                    if (read > 0) increment(read);
                    return read;
                }

                private void increment(int amount) throws RequestBodyLimitExceededException {
                    count += amount;
                    if (count > limit) {
                        throw new RequestBodyLimitExceededException(
                            "Request body exceeds the configured limit");
                    }
                }

                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
