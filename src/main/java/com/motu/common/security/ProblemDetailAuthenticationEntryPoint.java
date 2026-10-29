package com.motu.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.motu.common.error.ErrorCode;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 토큰이 없거나 잘못됐을 때도 다른 에러와 같은 Problem Details 형식으로 답한다
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private static final ErrorCode ERROR = ErrorCode.UNAUTHORIZED;

	private static final String BODY = """
		{"type":"about:blank","title":"%s","status":%d,"code":"%s"}"""
		.formatted(ERROR.title(), ERROR.status().value(), ERROR.name());

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setStatus(ERROR.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(BODY);
	}

}
