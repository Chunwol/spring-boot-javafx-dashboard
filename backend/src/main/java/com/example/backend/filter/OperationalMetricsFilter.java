package com.example.backend.filter;

import com.example.backend.service.status.OperationalMetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 요청별 운영 메트릭(응답 시간, 전송 바이트, 역할별 요청 수)을 수집하는 서블릿 필터.
 *
 * <p>수집 항목:
 * <ul>
 *   <li><b>API 요청</b>: 호출자 역할(ADMIN/USER/VIEWER/AGENT/ANON), HTTP 상태 코드, 처리 시간(나노초)</li>
 *   <li><b>파일 전송</b>: 스크립트·에이전트 업로드/다운로드 엔드포인트의 실제 바이트 수와 처리 시간</li>
 * </ul>
 *
 * <p>바이트 측정 방법:
 * {@link CountingHttpServletRequestWrapper}와 {@link CountingHttpServletResponseWrapper}로
 * 실제 읽고 쓴 바이트를 원자적으로 카운트한다.
 * 측정값이 0인 경우 {@code Content-Length} 헤더를 폴백으로 사용한다.
 *
 * <p>적용 범위: {@code /api/} 접두사를 가진 경로에만 필터가 실행된다.
 * {@code @Order(LOWEST_PRECEDENCE)}로 다른 필터(보안, 인증 등)가 모두 완료된 후 메트릭을 기록한다.
 *
 * @see OperationalMetricsService 메트릭 저장·집계
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class OperationalMetricsFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final OperationalMetricsService operationalMetricsService;

    public OperationalMetricsFilter(OperationalMetricsService operationalMetricsService) {
        this.operationalMetricsService = operationalMetricsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = normalizeUri(request);
        return !uri.startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CountingHttpServletRequestWrapper requestWrapper = new CountingHttpServletRequestWrapper(request);
        CountingHttpServletResponseWrapper responseWrapper = new CountingHttpServletResponseWrapper(response);
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            try {
                responseWrapper.flushBuffer();
            } catch (IOException ignored) {
                // Metrics collection should not mask the original response handling path.
            }
            long elapsedNanos = Math.max(0L, System.nanoTime() - startedAt);
            int statusCode = responseWrapper.getStatus();
            operationalMetricsService.recordApiRequest(resolveRole(requestWrapper), statusCode, elapsedNanos);

            String endpointGroup = resolveTransferEndpointGroup(requestWrapper);
            if (endpointGroup != null) {
                String direction = "GET".equalsIgnoreCase(requestWrapper.getMethod()) ? "download" : "upload";
                long bytes = resolveTransferBytes(direction, requestWrapper, responseWrapper);
                operationalMetricsService.recordTransfer(direction, bytes, elapsedNanos, statusCode, endpointGroup);
            }
        }
    }

    private String resolveRole(HttpServletRequest request) {
        Object agentMac = request.getAttribute("agentMac");
        if (agentMac != null && !String.valueOf(agentMac).isBlank()) {
            return "AGENT";
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "ANON";
        }
        if ("anonymousUser".equalsIgnoreCase(String.valueOf(authentication.getPrincipal()))) {
            return "ANON";
        }

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                String role = authority.getAuthority();
                if ("ROLE_ADMIN".equalsIgnoreCase(role)) {
                    return "ADMIN";
                }
                if ("ROLE_USER".equalsIgnoreCase(role)) {
                    return "USER";
                }
                if ("ROLE_VIEWER".equalsIgnoreCase(role)) {
                    return "VIEWER";
                }
            }
        }
        return "ANON";
    }

    private String resolveTransferEndpointGroup(HttpServletRequest request) {
        String uri = normalizeUri(request);
        if (uri.equals("/api/v1/scripts")) {
            return "SCRIPT_UPLOAD";
        }
        if (uri.equals("/api/v1/agent/uploads")) {
            return "AGENT_UPLOAD";
        }
        if (uri.equals("/api/v1/file-chunks") || uri.equals("/api/v1/agent/file-chunks")) {
            return "CHUNK_UPLOAD";
        }
        if (uri.equals("/api/v1/file-chunks/assemble") || uri.equals("/api/v1/agent/file-chunks/assemble")) {
            return "CHUNK_ASSEMBLE";
        }
        if (uri.matches("^/api/v1/scripts/\\d+/download$")) {
            return "SCRIPT_DOWNLOAD";
        }
        if (uri.matches("^/api/v1/agent/uploads/\\d+/download$")) {
            return "AGENT_UPLOAD_DOWNLOAD";
        }
        return null;
    }

    private long resolveTransferBytes(String direction,
                                      CountingHttpServletRequestWrapper request,
                                      CountingHttpServletResponseWrapper response) {
        if ("upload".equals(direction)) {
            long measured = request.getBytesRead();
            if (measured > 0L) {
                return measured;
            }
            long contentLength = request.getContentLengthLong();
            return Math.max(0L, contentLength);
        }

        long measured = response.getBytesWritten();
        if (measured > 0L) {
            return measured;
        }
        String contentLengthHeader = response.getHeader("Content-Length");
        if (contentLengthHeader == null || contentLengthHeader.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(contentLengthHeader.trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String normalizeUri(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        return uri.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CountingHttpServletRequestWrapper extends HttpServletRequestWrapper {
        private final AtomicLong bytesRead = new AtomicLong();
        private ServletInputStream countingInputStream;
        private BufferedReader reader;

        private CountingHttpServletRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called on this request.");
            }
            if (countingInputStream == null) {
                countingInputStream = new CountingServletInputStream(super.getInputStream(), bytesRead);
            }
            return countingInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (countingInputStream != null && reader == null) {
                throw new IllegalStateException("getInputStream() has already been called on this request.");
            }
            if (reader == null) {
                Charset charset = resolveRequestCharset(getCharacterEncoding());
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }

        private long getBytesRead() {
            return Math.max(0L, bytesRead.get());
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final AtomicLong bytesRead;

        private CountingServletInputStream(ServletInputStream delegate, AtomicLong bytesRead) {
            this.delegate = delegate;
            this.bytesRead = bytesRead;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                bytesRead.incrementAndGet();
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                bytesRead.addAndGet(count);
            }
            return count;
        }

        @Override
        public int read(byte[] b) throws IOException {
            int count = delegate.read(b);
            if (count > 0) {
                bytesRead.addAndGet(count);
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }

    private static final class CountingHttpServletResponseWrapper extends HttpServletResponseWrapper {
        private final AtomicLong bytesWritten = new AtomicLong();
        private ServletOutputStream countingOutputStream;
        private PrintWriter writer;

        private CountingHttpServletResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called on this response.");
            }
            if (countingOutputStream == null) {
                countingOutputStream = new CountingServletOutputStream(super.getOutputStream(), bytesWritten);
            }
            return countingOutputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (countingOutputStream != null && writer == null) {
                throw new IllegalStateException("getOutputStream() has already been called on this response.");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), resolveResponseCharset(getCharacterEncoding())), false);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (countingOutputStream != null) {
                countingOutputStream.flush();
            }
            super.flushBuffer();
        }

        private long getBytesWritten() {
            return Math.max(0L, bytesWritten.get());
        }
    }

    private static final class CountingServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final AtomicLong bytesWritten;

        private CountingServletOutputStream(ServletOutputStream delegate, AtomicLong bytesWritten) {
            this.delegate = delegate;
            this.bytesWritten = bytesWritten;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            if (len > 0) {
                bytesWritten.addAndGet(len);
            }
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            if (b != null && b.length > 0) {
                bytesWritten.addAndGet(b.length);
            }
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }

    private static Charset resolveRequestCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Charset resolveResponseCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception ex) {
            return StandardCharsets.UTF_8;
        }
    }
}
