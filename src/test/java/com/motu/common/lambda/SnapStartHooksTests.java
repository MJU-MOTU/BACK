package com.motu.common.lambda;

import java.time.Duration;

import javax.sql.DataSource;

import com.motu.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Lambda Web Adapter가 SnapStart 스냅샷 전·복원 후에 부르는 순서를 그대로 따라 한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "lambda" })
@Import(TestcontainersConfiguration.class)
class SnapStartHooksTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DataSource dataSource;

	@Test
	void 스냅샷_전에_커넥션을_모두_닫고_복원하면_다시_DB를_쓴다() throws Exception {
		HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
		mockMvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
		assertThat(pool.getTotalConnections()).isPositive();

		mockMvc.perform(post(SnapStartHooks.BEFORE_CHECKPOINT)).andExpect(status().isNoContent());
		assertThat(pool.getTotalConnections()).isZero();
		// 멈춘 동안에는 풀이 커넥션을 다시 채우지 않아야 스냅샷에 들어가지 않는다
		Thread.sleep(Duration.ofSeconds(2));
		assertThat(pool.getTotalConnections()).isZero();

		mockMvc.perform(post(SnapStartHooks.AFTER_RESTORE)).andExpect(status().isNoContent());
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
		assertThat(pool.getTotalConnections()).isPositive();

		// 복원된 실행 환경에서 누가 스냅샷 전 훅을 다시 불러도 풀을 멈추지 않는다
		mockMvc.perform(post(SnapStartHooks.BEFORE_CHECKPOINT)).andExpect(status().isConflict());
		mockMvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void lambda_프로필은_실행_환경당_커넥션을_2개까지만_쓴다() throws Exception {
		assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(2);
	}

}
