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
| `KIS_ENABLED` | 한국투자증권 Open API로 실시간 시세를 받을지 | `false` |
| `KIS_APP_KEY` / `KIS_APP_SECRET` | KIS 앱키 (`KIS_ENABLED=true`일 때 필수) | 없음 |
| `KIS_BASE_URL` | KIS 주소 | 실전 `https://openapi.koreainvestment.com:9443` |

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 회원가입 |
| POST | `/api/v1/auth/login` | 로그인 (Access 15분 + Refresh 14일) |
| POST | `/api/v1/auth/refresh` | 토큰 재발급 (Refresh 토큰 회전) |
| POST | `/api/v1/auth/logout` | Refresh 토큰 폐기 |
| GET | `/api/v1/me` | 내 정보 (`Authorization: Bearer`) |
| GET | `/api/v1/stocks?query=&market=&page=&size=` | 종목 검색 (이름 부분 일치, 코드 앞부분 일치). 가격은 저장된 마지막 가격 |
| GET | `/api/v1/stocks/{code}` | 종목 상세 + 현재가 (KIS → 실패 시 저장된 마지막 가격) |
| GET | `/api/v1/stocks/{code}/candles?from=&to=` | 일봉 (기본 최근 3개월) |
| POST | `/api/v1/orders` | 시장가 매수·매도 (`Idempotency-Key` 헤더 필수). 새 체결 201, 같은 키 재요청 200 |
| GET | `/api/v1/orders?page=&size=` | 주문 내역 (거절 포함, 최신순) |
| GET | `/api/v1/wallet` | 현금 잔고 |
| GET | `/api/v1/wallet/ledger?page=&size=` | 원장 (잔고가 바뀐 이유, 최신순) |
| GET | `/api/v1/portfolio` | 보유 종목별 평균단가·평가금액·손익 + 총자산 |

## 주문·지갑

- 가입하면 가상 현금 1,000만 원이 든 지갑과 원장 첫 줄(INITIAL)이 생깁니다.
- 주문은 지갑 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 체결합니다. 같은 회원의 주문은 한 줄로 처리됩니다.
- 시세 조회(KIS HTTP)는 잠금 전에 끝냅니다. 체결 트랜잭션은 첫 문장이 지갑 잠금이고 격리 수준은 READ COMMITTED입니다. MySQL 기본값(REPEATABLE READ)은 잠금 전에 찍힌 스냅샷을 계속 보므로, 앞 주문이 바꾼 보유 수량을 못 볼 수 있습니다.
- 잔고는 원장 합계와 항상 같습니다. 금액은 원 단위 `long`이고, 평균단가는 저장하지 않고 총매입금액에서 계산합니다.
- 장이 끝났거나 KIS가 실패하면 마지막으로 받은 가격으로 체결하고 `priceSource: STORED`로 남깁니다. 수수료는 0원입니다.

## 시세

- 현재가는 5초 동안 메모리에 캐시하고, 받은 값은 `stock_prices`에 마지막 가격으로 저장합니다.
- KIS가 꺼져 있거나 실패하면 저장된 마지막 가격을 `source: STORED`로 돌려줍니다. 받아 본 적이 없으면 `quote: null`입니다. 가짜 가격은 만들지 않습니다.
- 종목 목록은 페이지마다 KIS를 여러 번 부르지 않도록 저장된 가격만 씁니다.

에러는 RFC 9457 Problem Details 형식이며, 프론트는 `code` 필드로 분기합니다.

## 테스트

```bash
./gradlew test
```

테스트는 Testcontainers로 MySQL 컨테이너를 직접 띄우므로 `docker compose` 없이 돌아갑니다.
