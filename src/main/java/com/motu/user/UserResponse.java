package com.motu.user;

import java.time.Instant;

public record UserResponse(Long id, String email, String nickname, Instant createdAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt());
	}

}
