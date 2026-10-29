package com.motu.common.error;

public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.title());
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

}
