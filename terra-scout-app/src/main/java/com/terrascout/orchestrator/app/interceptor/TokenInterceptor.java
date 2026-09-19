package com.terrascout.orchestrator.app.interceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.security.TokenValidator;
import com.terrascout.orchestrator.app.security.TraceContext;
import com.terrascout.orchestrator.core.error.TerraScoutError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 鉴权拦截器。
 *
 * <p>校验失败统一返回 HTTP 401 + 业务码 401001（HTTP 状态码与错误码前 3 位强一致）。
 * 免鉴权白名单仅 {@code GET /api/v1/health}；其余路径全部经此项拦截，防止新端点漏鉴权。
 */
@Component
public class TokenInterceptor implements HandlerInterceptor {

    private final TokenValidator validator;
    private final ObjectMapper objectMapper;

    public TokenInterceptor(TokenValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        TraceContext.begin();
        String method = request.getMethod();
        String path = request.getServletPath();

        if (validator.isWhitelisted(method, path)) {
            return true;
        }
        String token = request.getHeader(TokenValidator.HEADER_TOKEN);
        if (validator.isTokenValid(token)) {
            return true;
        }
        writeUnauthorized(response);
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        TraceContext.clear();
    }

    /** 写 401 + 401001 JSON，HTTP 状态码与业务码前缀强一致。 */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(TerraScoutError.TOKEN_INVALID.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        String body = objectMapper.writeValueAsString(
                com.terrascout.orchestrator.core.dto.ApiResponse.fail(TerraScoutError.TOKEN_INVALID));
        response.getWriter().write(body);
    }
}
