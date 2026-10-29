package com.motu.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.motu.auth.AuthDtos.LoginRequest;
import com.motu.auth.AuthDtos.SignupRequest;
import com.motu.auth.AuthDtos.SignupResponse;
import com.motu.auth.AuthDtos.TokenResponse;
import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.user.User;
import com.motu.user.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenIssuer tokenIssuer;
	private final Clock clock;

	// 없는 이메일로 로그인해도 비밀번호 비교 시간을 들여, 응답 시간으로 가입 여부를 알 수 없게 한다
	private final String dummyPasswordHash;

	AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
			PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer, Clock clock) {
		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenIssuer = tokenIssuer;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		String email = normalize(request.email());
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
		User user = User.create(email, passwordEncoder.encode(request.password()), request.nickname().strip(),
				clock.instant());
		try {
			// 동시에 같은 이메일로 가입하면 위 확인을 둘 다 통과한다. 그때는 UNIQUE 제약이 막는다
			userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
		return new SignupResponse(user.getId(), user.getEmail(), user.getNickname());
	}

	@Transactional
	public TokenResponse login(LoginRequest request) {
		Optional<User> user = userRepository.findByEmail(normalize(request.email()));
		String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
		boolean matches = passwordEncoder.matches(request.password(), hash);
		if (user.isEmpty() || !matches) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}
		return issueTokens(user.get().getId(), clock.instant());
	}

	// 재발급할 때마다 Refresh 토큰을 새로 바꾼다(회전). 이미 바꾼 토큰이 다시 오면 탈취로 보고 그 회원의 토큰을 모두 폐기한다.
	// 폐기는 예외를 던진 뒤에도 남아야 하므로 BusinessException으로는 롤백하지 않는다
	@Transactional(noRollbackFor = BusinessException.class)
	public TokenResponse refresh(String rawRefreshToken) {
		Instant now = clock.instant();
		RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(TokenIssuer.hash(rawRefreshToken))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		if (token.isRevoked()) {
			refreshTokenRepository.revokeAllActiveByUserId(token.getUserId(), now);
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		if (token.isExpired(now)) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		token.revoke(now);
		return issueTokens(token.getUserId(), now);
	}

	// 이미 폐기됐거나 없는 토큰이어도 결과는 같다(로그아웃된 상태)
	@Transactional
	public void logout(String rawRefreshToken) {
		refreshTokenRepository.findByTokenHash(TokenIssuer.hash(rawRefreshToken))
			.ifPresent(token -> token.revoke(clock.instant()));
	}

	private TokenResponse issueTokens(Long userId, Instant now) {
		TokenIssuer.IssuedToken access = tokenIssuer.issueAccessToken(userId, now);
		TokenIssuer.IssuedToken refresh = tokenIssuer.newRefreshToken(now);
		refreshTokenRepository.save(RefreshToken.issue(userId, TokenIssuer.hash(refresh.value()), refresh.expiresAt(), now));
		return TokenResponse.bearer(access, refresh);
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
