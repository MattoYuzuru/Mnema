package app.mnema.identityaccount.authorization;

import app.mnema.identityaccount.security.Secrets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * Serializes a token's validation, one-use consumption and successor persistence across replicas.
 */
public final class GrantTransactionFilter extends OncePerRequestFilter {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;

    public GrantTransactionFilter(JdbcClient jdbcClient, TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/oauth2/token") || !request.getMethod().equals("POST");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String credential = request.getParameter("code");
        if (credential == null) credential = request.getParameter("refresh_token");
        if (credential == null) {
            chain.doFilter(request, response);
            return;
        }
        String lock = Secrets.hash(credential);
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(s -> {
                jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,142))").param("key", lock).query(rs -> {
                    rs.next();
                    return 0;
                });
                try {
                    chain.doFilter(request, buffered);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
            });
        } catch (FilterFailure e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw (ServletException) e.getCause();
        }
        buffered.copyBodyToResponse();
    }

    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
