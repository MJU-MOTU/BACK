package com.motu.common.error;

import org.springframework.http.ProblemDetail;

// 에러 응답은 RFC 9457 Problem Details에 code 필드를 더한 형태로 통일한다
public final class Problems {

	private Problems() {
	}

	public static ProblemDetail of(ErrorCode errorCode) {
		ProblemDetail problem = ProblemDetail.forStatus(errorCode.status());
		problem.setTitle(errorCode.title());
		problem.setProperty("code", errorCode.name());
		return problem;
	}

}
