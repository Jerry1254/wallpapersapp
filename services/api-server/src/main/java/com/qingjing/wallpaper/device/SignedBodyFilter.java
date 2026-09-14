package com.qingjing.wallpaper.device;

import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SignedBodyFilter extends OncePerRequestFilter {

    private static final int MAX_SIGNED_BODY_BYTES = 64 * 1024;
    private final HandlerExceptionResolver exceptionResolver;

    public SignedBodyFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SignedDeviceRoutes.requiresSignature(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_SIGNED_BODY_BYTES + 1);
            if (body.length > MAX_SIGNED_BODY_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The signed request body is too large");
            }
            request.setAttribute(RequestAttributes.SIGNED_BODY_BYTES, body);
            filterChain.doFilter(new ReplayableRequest(request, body), response);
        } catch (ApiException exception) {
            exceptionResolver.resolveException(request, response, null, exception);
        }
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
