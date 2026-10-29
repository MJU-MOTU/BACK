# MOTU Backend

모의투자와 투자 학습을 한 흐름으로 묶은 서비스 MOTU(모투)의 API 서버입니다.

| 항목 | 내용 |
| --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.1 |
| DB | MySQL 8.4, Flyway |
| 테스트 | JUnit 5, Testcontainers(MySQL) |
| 프론트엔드 | [MJU-MOTU/FRONT](https://github.com/MJU-MOTU/FRONT) |

## 로컬 실행

Docker와 JDK 21이 필요합니다.

```bash
docker compose up -d
./gradlew bootRun
```

- 헬스체크: http://localhost:8080/actuator/health

DB 접속 정보는 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 바꿀 수 있습니다. 기본값은 `compose.yaml`의 MySQL입니다.

## 테스트

```bash
./gradlew test
```

테스트는 Testcontainers로 MySQL 컨테이너를 직접 띄우므로 `docker compose` 없이 돌아갑니다.
