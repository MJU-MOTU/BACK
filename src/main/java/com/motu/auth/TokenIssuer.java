package com.motu.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import com.motu.common.security.JwtProperties;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;

	TokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public IssuedToken issueAccessToken(Long userId, Instant now) {
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("motu")
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(value, expiresAt);
	}

	// Refresh 토큰은 JWT가 아니라 256비트 난수다. 서버가 DB에서 직접 확인하므로 안에 정보를 담을 필요가 없다
	public IssuedToken newRefreshToken(Instant now) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new IssuedToken(value, now.plus(properties.refreshTokenTtl()));
	}

	public static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public record IssuedToken(String value, Instant expiresAt) {
	}

}
