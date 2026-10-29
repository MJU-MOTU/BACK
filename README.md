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
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 헬스체크: http://localhost:8080/actuator/health
- `local` 프로필은 개발용 JWT 비밀키를 씁니다. 프로필 없이 띄우면 `JWT_SECRET`이 없어서 서버가 시작되지 않습니다.

## 환경 변수

| 이름 | 설명 | 기본값 |
| --- | --- | --- |
| `DB_URL` | JDBC URL | `jdbc:mysql://localhost:3306/motu` |
| `DB_USERNAME` / `DB_PASSWORD` | DB 계정 | `motu` / `motu` |
| `JWT_SECRET` | Access 토큰 서명 키 (32바이트 이상) | 없음 (필수) |

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 회원가입 |
| POST | `/api/v1/auth/login` | 로그인 (Access 15분 + Refresh 14일) |
| POST | `/api/v1/auth/refresh` | 토큰 재발급 (Refresh 토큰 회전) |
| POST | `/api/v1/auth/logout` | Refresh 토큰 폐기 |
| GET | `/api/v1/me` | 내 정보 (`Authorization: Bearer`) |

에러는 RFC 9457 Problem Details 형식이며, 프론트는 `code` 필드로 분기합니다.

## 테스트

```bash
./gradlew test
```

테스트는 Testcontainers로 MySQL 컨테이너를 직접 띄우므로 `docker compose` 없이 돌아갑니다.
