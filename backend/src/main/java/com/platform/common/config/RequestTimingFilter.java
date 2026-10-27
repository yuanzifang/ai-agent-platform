package com.platform.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * 慢请求首字节观测：默认关闭，仅用于压测/性能定位。
 * 对 SSE 响应也有效：在首次写入响应体时记录从过滤器进入到首字节的耗时。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequestTimingFilter extends OncePerRequestFilter {

    @Value("${request.timing.enabled:false}")
    private boolean enabled;

    @Value("${request.timing.slow-ms:1000}")
    private long slowMs;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        FirstByteResponseWrapper wrapped = new FirstByteResponseWrapper(response, firstByteNanos -> {
            long elapsedMs = (firstByteNanos - start) / 1_000_000L;
            if (elapsedMs >= slowMs) {
                log.warn("请求首字节慢 method={} uri={} firstByteMs={}", method, uri, elapsedMs);
            }
        });
        filterChain.doFilter(request, wrapped);
    }

    private static final class FirstByteResponseWrapper extends HttpServletResponseWrapper {
        private final LongConsumer onFirstByte;
        private final AtomicBoolean fired = new AtomicBoolean();
        private ServletOutputStream output;
        private PrintWriter writer;

        private FirstByteResponseWrapper(HttpServletResponse response, LongConsumer onFirstByte) {
            super(response);
            this.onFirstByte = onFirstByte;
        }

        private void fire() {
            if (fired.compareAndSet(false, true)) onFirstByte.accept(System.nanoTime());
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                ServletOutputStream delegate = super.getOutputStream();
                output = new ServletOutputStream() {
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                    @Override public void write(int b) throws IOException { fire(); delegate.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        fire(); delegate.write(b, off, len);
                    }
                    @Override public void flush() throws IOException { delegate.flush(); }
                    @Override public void close() throws IOException { delegate.close(); }
                };
            }
            return output;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                Writer delegate = super.getWriter();
                writer = new PrintWriter(new Writer() {
                    @Override public void write(char[] cbuf, int off, int len) throws IOException {
                        fire(); delegate.write(cbuf, off, len);
                    }
                    @Override public void flush() throws IOException { delegate.flush(); }
                    @Override public void close() throws IOException { delegate.close(); }
                });
            }
            return writer;
        }
    }
}
