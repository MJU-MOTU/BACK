package com.motu.common.error;

import org.springframework.http.HttpStatus;

// 프론트는 응답 본문의 code 값으로 분기한다. 이름을 바꾸면 프론트도 같이 바꿔야 한다
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "주문에는 Idempotency-Key 헤더가 필요합니다"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다"),
	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다"),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 Idempotency-Key로 다른 주문을 보냈습니다"),
	INSUFFICIENT_CASH(HttpStatus.UNPROCESSABLE_CONTENT, "잔고가 부족합니다"),
	INSUFFICIENT_QUANTITY(HttpStatus.UNPROCESSABLE_CONTENT, "보유 수량이 부족합니다"),
	PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 시세를 가져올 수 없습니다");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

}
