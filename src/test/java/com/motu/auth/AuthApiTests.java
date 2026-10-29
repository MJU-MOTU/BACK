package com.motu.auth;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.motu.IntegrationTest;
import com.motu.user.UserRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuthApiTests {

	private static final String PASSWORD = "motu-pass-1234";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository userRepository;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void 가입하면_201과_회원정보를_돌려주고_비밀번호는_해시로_저장한다() throws Exception {
		String email = newEmail();

		signup(email, PASSWORD, "모투")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.nickname").value("모투"));

		String stored = userRepository.findByEmail(email).orElseThrow().getPasswordHash();
		assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
	}

	@Test
	void 이미_가입된_이메일이면_대소문자가_달라도_409다() throws Exception {
		String email = newEmail();
		signup(email, PASSWORD, "모투").andExpect(status().isCreated());

		signup(email.toUpperCase(), PASSWORD, "모투2")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
	}

	@Test
	void 입력이_잘못되면_400과_잘못된_필드를_알려준다() throws Exception {
		signup("not-an-email", "short", "모")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(3));
	}

	@Test
	void 로그인하면_받은_Access_토큰으로_내_정보를_조회한다() throws Exception {
		String email = newEmail();
		signup(email, PASSWORD, "모투");

		String body = login(email, PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andReturn().getResponse().getContentAsString();
		String accessToken = JsonPath.read(body, "$.accessToken");

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void 비밀번호가_틀려도_없는_이메일이어도_같은_401이다() throws Exception {
		String email = newEmail();
		signup(email, PASSWORD, "모투");

		login(email, "wrong-password-1")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		login(newEmail(), PASSWORD)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void 토큰이_없거나_만료됐으면_401_UNAUTHORIZED다() throws Exception {
		mockMvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject("1")
			.issuedAt(past.minusSeconds(900))
			.expiresAt(past)
			.build();
		String expired = jwtEncoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void 재발급하면_새_토큰을_주고_이전_Refresh_토큰은_다시_쓸_수_없다() throws Exception {
		String first = loginAndGetRefreshToken();

		String second = JsonPath.read(refresh(first)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(), "$.refreshToken");

		assertThat(second).isNotEqualTo(first);
		refresh(first)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void 이미_쓴_Refresh_토큰이_다시_오면_그_회원의_토큰을_모두_폐기한다() throws Exception {
		String first = loginAndGetRefreshToken();
		String second = JsonPath.read(refresh(first).andReturn().getResponse().getContentAsString(), "$.refreshToken");

		// 탈취된 first를 누군가 다시 쓴다
		refresh(first).andExpect(status().isUnauthorized());

		// 정상 사용자가 가진 second도 함께 막힌다
		refresh(second)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void 로그아웃한_Refresh_토큰으로는_재발급할_수_없다() throws Exception {
		String refreshToken = loginAndGetRefreshToken();

		mockMvc.perform(post("/api/v1/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"refreshToken":"%s"}""".formatted(refreshToken)))
			.andExpect(status().isNoContent());

		refresh(refreshToken).andExpect(status().isUnauthorized());
	}

	private String loginAndGetRefreshToken() throws Exception {
		String email = newEmail();
		signup(email, PASSWORD, "모투");
		return JsonPath.read(login(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.refreshToken");
	}

	private ResultActions signup(String email, String password, String nickname) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","nickname":"%s"}""".formatted(email, password, nickname)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s"}""".formatted(email, password)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"refreshToken":"%s"}""".formatted(refreshToken)));
	}

	private static String newEmail() {
		return "user-" + UUID.randomUUID() + "@motu.test";
	}

}
