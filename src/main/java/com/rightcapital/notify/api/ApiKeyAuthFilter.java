package com.rightcapital.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.common.apierror.ApiError;
import com.rightcapital.notify.common.apierror.ErrorCode;
import com.rightcapital.notify.common.idgen.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * API Key 鉴权过滤器（T3.2）。
 *
 * <p>鉴权放在最外层的原因：caller_id 是幂等域（uk_idem 前缀）、限流桶、
 * 数据隔离三件事的键，必须在进入任何业务逻辑前就确定。</p>
 *
 * <p>同时在这里注入 traceId（ULID）到 MDC 与响应头 ——
 * 错误响应里的 traceId 能直接 grep 到日志（T1.5 的闭环）。</p>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String TRACE_HEADER = "X-Trace-Id";

    private final Map<String, String> callerKeys;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(Map<String, String> callerKeys, ObjectMapper objectMapper) {
        this.callerKeys = callerKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // actuator 只做运维探活，不参与业务，不要求鉴权
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Ulid.next();
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            String apiKey = request.getHeader(API_KEY_HEADER);
            String callerId = apiKey == null ? null : callerKeys.get(apiKey);
            if (callerId == null) {
                // 不区分「没带 key」和「key 错」：给攻击者的信息越少越好
                writeError(response, traceId);
                return;
            }
            CallerContext.set(callerId);
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
            MDC.remove("traceId");
        }
    }

    private void writeError(HttpServletResponse response, String traceId) throws IOException {
        // 手写 Content-Length：缺失时部分客户端（如 RestTemplate 流式模式）会对 401 走认证重试，抛 I/O 错
        byte[] payload = objectMapper.writeValueAsBytes(
                ApiError.of(ErrorCode.UNAUTHORIZED, "缺少或无效的 " + API_KEY_HEADER, traceId));
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
    }
}
