package com.motu.common.lambda;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.jdbc.HikariCheckpointRestoreLifecycle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// Lambda SnapStart 훅. Lambda Web Adapter가 스냅샷을 찍기 직전과 복원한 직후에 빈 POST로 부른다.
// 스냅샷에 열린 DB 커넥션이 들어가면 복원된 실행 환경들이 이미 끊긴 소켓을 나눠 쓰게 되므로, 찍기 전에 풀을 멈추고 비운다
@RestController
@Profile("lambda")
class SnapStartHooks {

	static final String BEFORE_CHECKPOINT = "/internal/snapstart/before-checkpoint";
	static final String AFTER_RESTORE = "/internal/snapstart/after-restore";

	private static final Logger log = LoggerFactory.getLogger(SnapStartHooks.class);

	private final HikariCheckpointRestoreLifecycle connectionPool;

	// 스냅샷은 초기화 때 한 번만 찍는다. 이 값까지 스냅샷에 들어가므로, 복원된 뒤에 누가 다시 불러도 풀을 멈추지 않는다
	private final AtomicBoolean checkpointed = new AtomicBoolean();

	SnapStartHooks(DataSource dataSource, ConfigurableApplicationContext context) {
		this.connectionPool = new HikariCheckpointRestoreLifecycle(dataSource, context);
	}

	@PostMapping(BEFORE_CHECKPOINT)
	ResponseEntity<Void> beforeCheckpoint() {
		if (!checkpointed.compareAndSet(false, true)) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		connectionPool.stop();
		log.info("SnapStart 스냅샷 전: DB 커넥션 풀을 멈추고 커넥션을 닫았습니다");
		return ResponseEntity.noContent().build();
	}

	@PostMapping(AFTER_RESTORE)
	ResponseEntity<Void> afterRestore() {
		connectionPool.start();
		log.info("SnapStart 복원 후: DB 커넥션 풀을 다시 열었습니다");
		return ResponseEntity.noContent().build();
	}

}
