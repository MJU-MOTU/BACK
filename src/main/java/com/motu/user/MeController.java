package com.motu.user;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

	private final UserRepository userRepository;

	MeController(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	// Access 토큰의 subject가 회원 ID다
	@GetMapping("/api/v1/me")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		Long userId = Long.valueOf(jwt.getSubject());
		return userRepository.findById(userId)
			.map(UserResponse::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

}
