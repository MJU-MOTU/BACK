package com.motu.auth;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	private AuthDtos() {
	}

	// BCrypt는 72바이트까지만 쓴다. 비밀번호를 공백 없는 ASCII로 제한하면 64자여도 72바이트를 넘지 않는다
	public record SignupRequest(
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Pattern(regexp = "^[\\x21-\\x7E]{8,64}$", message = "비밀번호는 공백 없는 영문·숫자·기호 8~64자입니다") String password,
			@NotBlank @Size(min = 2, max = 20) String nickname) {
	}

	public record SignupResponse(Long userId, String email, String nickname) {
	}

	public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	public record RefreshRequest(@NotBlank String refreshToken) {
	}

	public record TokenResponse(
			String tokenType,
			String accessToken,
			Instant accessTokenExpiresAt,
			String refreshToken,
			Instant refreshTokenExpiresAt) {

		static TokenResponse bearer(TokenIssuer.IssuedToken access, TokenIssuer.IssuedToken refresh) {
			return new TokenResponse("Bearer", access.value(), access.expiresAt(), refresh.value(), refresh.expiresAt());
		}

	}

}
