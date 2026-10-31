# 배포

| 대상 | 어디에 | 설정 |
| --- | --- | --- |
| 백엔드 | AWS Lambda (서울, Java 21, arm64) + SnapStart + Lambda Web Adapter, 주소는 Function URL | `deploy/lambda/template.yaml`(SAM), `deploy/lambda/run.sh` |
| DB | Amazon RDS for MySQL 8.4 (서울, `db.t4g.micro`) | 아래 3단계 |
| 프론트엔드 | Vercel (Hobby) | FRONT의 `vercel.json` |

결정 과정은 노션 ADR "백엔드 배포: Lambda(SnapStart) + RDS, 프론트: Vercel"에 있습니다. `Dockerfile`과 `compose.yaml`은 로컬 실행과 CI 확인용으로 그대로 두었습니다. EC2 같은 서버로 옮길 때도 그대로 쓸 수 있습니다.

```
브라우저 ── 정적 파일 ──> Vercel
   └──── /api/v1/** ──> Function URL ──> Lambda [Web Adapter ──HTTP──> Spring Boot :8080] ──TLS──> RDS MySQL
                                                                                     └──> KIS Open API
```

## Lambda에서 달라지는 점과 대응

| 달라지는 점 | 대응 |
| --- | --- |
| 서버가 뜨는 데 10.7초가 걸린다(Lambda Java 21 런타임 이미지로 잰 값). Lambda는 초기화를 10초로 자른다 | SnapStart. 버전을 게시할 때 한 번 초기화해 스냅샷을 찍고, 요청이 오면 스냅샷에서 복원한다 |
| 스냅샷에 열린 DB 커넥션이 들어가면, 복원된 실행 환경들이 이미 끊긴 소켓을 나눠 쓰게 된다 | `SnapStartHooks`: 스냅샷 전에 커넥션 풀을 멈추고 모두 닫고, 복원 후에 다시 연다(Web Adapter가 POST로 부른다) |
| 실행 환경이 여러 개 뜨고 메모리를 나누지 않는다. KIS 토큰을 환경마다 발급하면 발급 제한에 걸린다 | 토큰을 `kis_access_tokens` 한 행에 두고 `SELECT ... FOR UPDATE`로 한 곳에서만 발급한다. 8대가 동시에 요청해도 발급 1번(잠금을 빼면 8번) |
| 실행 환경 하나는 요청을 한 번에 하나만 처리한다 | `lambda` 프로필에서 커넥션 풀을 2개로 줄인다. 회원별 JVM 잠금은 효과가 없지만, 정확성은 지갑 행 잠금이 지킨다 |
| Web Adapter의 실행 래퍼를 쓰면 런타임이 기본으로 넣는 JVM 옵션(C1만 쓰기, 힙 크기)이 적용되지 않는다 | `run.sh`가 힙을 메모리의 75%로 정한다. Lambda는 설정한 메모리만큼 과금하므로 아낄 이유가 없다 |

## 비용

2026-10-31에 AWS 공식 요금 페이지에서 확인했습니다. "추정"은 공식 단가로 계산한 값입니다.

| 항목 | 비용 |
| --- | --- |
| Lambda | 매달 요청 100만 건, 400,000 GB-초까지 무료. 2GB면 매달 약 55시간 분량이라 데모는 0원. Java SnapStart는 추가 요금 없음 |
| Function URL | 별도 요금 없음 |
| RDS `db.t4g.micro`(서울) | 시간당 $0.025, 한 달 약 $18(추정) + 스토리지 20GB + 공인 IPv4 시간당 $0.005(한 달 약 $3.6) |
| 새 계정 Free 플랜 | 크레딧 $100 + 활동하면 최대 $100. 6개월이 지나거나 크레딧을 다 쓰면 계정이 닫히니, 계속 쓰려면 그 전에 유료 플랜으로 올린다 |

출처: [Lambda 요금](https://aws.amazon.com/lambda/pricing/) · [SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html) · [RDS MySQL 요금](https://aws.amazon.com/rds/mysql/pricing/) · [AWS Free 플랜](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html) · [Lambda Web Adapter](https://github.com/awslabs/aws-lambda-web-adapter)

## 직접 해야 할 일 (순서대로)

계정 생성, 결제 수단, 비밀값 입력은 사람이 해야 합니다. 콘솔 메뉴 이름은 바뀔 수 있습니다.

### 1. AWS 계정

1. AWS에 가입하고 Free 플랜을 고릅니다.
2. Billing → Budgets에서 월 $5 예산 알림을 만듭니다(크레딧이 줄어드는 걸 메일로 받기).
3. IAM에서 배포용 사용자를 만들고 액세스 키를 받아 로컬에서 `aws configure`로 등록합니다(리전 `ap-northeast-2`). 키는 어디에도 커밋하지 않습니다.

### 2. 로컬 도구

JDK 21, Docker, [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html), [SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)를 설치합니다.

### 3. RDS MySQL

1. RDS → Create database → Standard create → MySQL 8.4 → Free tier 템플릿 → `db.t4g.micro`, 스토리지 20GB.
2. Master username `admin`, 비밀번호는 직접 만든 긴 값.
3. Connectivity → Public access **Yes**, 새 보안 그룹. Additional configuration → Initial database name `motu`, 백업 보관 1~7일.
4. 만든 보안 그룹의 인바운드에 MySQL(3306)을 `0.0.0.0/0`으로 엽니다. VPC 밖 Lambda는 IP가 고정이 아니라서입니다. 대신 앱 계정은 TLS만 받게 하고 비밀번호를 길게 둡니다(아래 "보안에서 포기한 것").
5. 앱 계정을 만듭니다.

```bash
docker run --rm -it mysql:8.4 mysql -h <RDS 엔드포인트> -u admin -p --ssl-mode=REQUIRED
```

```sql
CREATE USER 'motu'@'%' IDENTIFIED BY '<긴 비밀번호>' REQUIRE SSL;
GRANT ALL PRIVILEGES ON motu.* TO 'motu'@'%';
```

### 4. 백엔드 배포

```bash
./gradlew lambdaZip
```

```bash
sam deploy -t deploy/lambda/template.yaml --guided
```

물어보는 값:

| 항목 | 값 |
| --- | --- |
| Stack Name | `motu-back` |
| AWS Region | `ap-northeast-2` |
| `DbUrl` | `jdbc:mysql://<RDS 엔드포인트>:3306/motu?sslMode=REQUIRED` |
| `DbUsername` / `DbPassword` | `motu` / 3단계에서 만든 비밀번호 |
| `JwtSecret` | `openssl rand -base64 48`로 만든 값 |
| `CorsAllowedOrigins` | 처음엔 비워 두고 5단계 뒤에 채운다 |
| `KisEnabled` 등 | 키가 없으면 기본값(`false`) |
| Function URL에 인증이 없다는 확인 질문 | `y` (공개 API이고, 인증은 JWT로 한다) |

- 버전을 게시할 때 SnapStart 초기화가 돌고, 이때 Flyway가 테이블과 종목 28개를 만듭니다. 배포에 몇 분 걸립니다.
- 끝나면 Outputs의 `ApiUrl`(`https://….lambda-url.ap-northeast-2.on.aws/`)로 `/actuator/health`가 `UP`인지 확인합니다.
- `--guided`가 만든 `samconfig.toml`에는 입력한 값이 남을 수 있어 `.gitignore`에 넣었습니다. 커밋하지 않습니다.

### 5. 프론트(Vercel)

1. Vercel에서 GitHub의 MJU-MOTU/FRONT를 가져옵니다(Framework Preset: Vite).
2. Environment Variables에 `VITE_API_BASE_URL=<ApiUrl>`을 넣고 배포합니다.

### 6. 서로 연결

```bash
sam deploy -t deploy/lambda/template.yaml --parameter-overrides CorsAllowedOrigins=https://<Vercel 주소>
```

`--parameter-overrides`에 적지 않은 값은 지난 배포 값을 그대로 씁니다. 프론트에서 회원가입 → 종목 상세 → 매수 → 포트폴리오까지 확인합니다.

## 다시 배포

```bash
./gradlew lambdaZip && sam deploy -t deploy/lambda/template.yaml
```

## 보안에서 포기한 것

- RDS 3306이 인터넷에 열려 있습니다. Lambda를 VPC에 넣고 RDS를 닫으면 KIS(인터넷)를 부르는 데 NAT 게이트웨이가 필요하고, 켜 둔 시간만큼 매달 고정비가 붙습니다. 대신 앱 계정은 TLS만 받고(`REQUIRE SSL`), 비밀번호를 길게 두고, 가상 돈만 다룹니다.
- `sslMode=REQUIRED`는 암호화만 하고 서버 인증서를 검증하지 않습니다. 검증하려면 RDS CA를 트러스트스토어에 넣고 `VERIFY_IDENTITY`로 바꿉니다.
- 비밀값은 Lambda 환경 변수(저장할 때 암호화됨)에 둡니다. 더 엄격하게 하려면 Secrets Manager로 옮깁니다(비밀 하나에 매달 요금이 붙음).

## 로컬에서 확인한 것

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 템플릿 | `cfn-lint`(SAM 변환 포함) | 경고 없음. CI에서도 돈다 |
| 런타임 | `public.ecr.aws/lambda/java:21` 이미지 + 런타임 에뮬레이터 + 실행 래퍼(`/opt/bootstrap`)로 `run.sh` 실행 | `lambda` 프로필로 10.7초 만에 뜨고 DB까지 UP, 가입·로그인 정상, 상주 메모리 약 470MB |
| 스냅샷 훅 | 같은 환경 + `SnapStartHooksTests` | 스냅샷 전 커넥션 0개(2초 뒤에도 0) → 복원 후 UP → 다시 부르면 409 |
| KIS 토큰 공유 | `KisTokenSharingTests`(서버 8대가 같은 DB) | 동시에 처음 요청해도 발급 1번 |

Web Adapter 자체(요청 변환)는 이 환경에서 이미지를 받지 못해 돌려 보지 못했습니다. 첫 배포 때 확인합니다.

## 배포한 뒤 잴 것

- 콜드 스타트: CloudWatch Logs의 `RESTORE_REPORT`(SnapStart 복원 시간)와 첫 요청 시간. 비교용으로 SnapStart를 끈 버전의 `INIT_REPORT`.
- 웜 상태 응답 시간과 주문 1건 시간(서울 Lambda ↔ 서울 RDS).
