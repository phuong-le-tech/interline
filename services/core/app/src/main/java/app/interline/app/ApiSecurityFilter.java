package app.interline.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Guards the local API before any controller runs: loopback Host only (DNS rebinding), exact allowed Origins
 * (no wildcard), then the per-launch bearer token compared in constant time. Preflights carry no token.
 */
final class ApiSecurityFilter extends OncePerRequestFilter {
    static final Set<String> BUILT_IN_ORIGINS = Set.of("tauri://localhost", "http://tauri.localhost");
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String ALLOWED_HEADERS = "authorization, content-type";
    private static final String PREFLIGHT_MAX_AGE_SECONDS = "600";
    private static final int MIN_TOKEN_LENGTH = 32;

    private final byte[] token;
    private final Set<String> allowedOrigins = new HashSet<>(BUILT_IN_ORIGINS);

    ApiSecurityFilter(String token, Iterable<String> extraOrigins) {
        if (token == null || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("interline.token must be supplied on stdin by the shell (at least 128 bits)");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        extraOrigins.forEach(origin -> allowedOrigins.add(origin.strip()));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return true; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return true; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(LOOPBACK_HOST + ":" + request.getLocalPort()).equals(request.getHeader("Host"))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Unexpected Host header");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Origin not allowed");
            return;
        }
        if (origin != null) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
        }
        if (origin != null && HttpMethod.OPTIONS.matches(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null) {
            response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
            response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
            response.setHeader("Access-Control-Max-Age", PREFLIGHT_MAX_AGE_SECONDS);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (!hasValidToken(request.getHeader("Authorization"))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "A valid bearer token is required");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hasValidToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) return false;
        byte[] presented = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private static void reject(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"}");
    }
}
