package com.motu.common.error;

import java.util.List;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ProblemDetail> handleBusiness(BusinessException e) {
		ErrorCode errorCode = e.errorCode();
		return ResponseEntity.status(errorCode.status()).body(Problems.of(errorCode));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleInvalid(MethodArgumentNotValidException e) {
		ProblemDetail problem = Problems.of(ErrorCode.VALIDATION_FAILED);
		List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest().body(Problems.of(ErrorCode.VALIDATION_FAILED));
	}

}
