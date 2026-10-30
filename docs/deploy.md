# 배포

| 대상 | 플랫폼 | 설정 파일 |
| --- | --- | --- |
| 백엔드 + MySQL | Railway (Hobby, 싱가포르 리전) | `Dockerfile`, `railway.json` |
| 프론트엔드 | Cloudflare Workers 정적 자산 | FRONT의 `wrangler.jsonc` |

플랫폼 선택은 아직 확정 전입니다(노션 ADR "배포 플랫폼", 검토중). Dockerfile과 환경 변수만으로 뜨게 만들어서 다른 플랫폼으로 옮겨도 코드는 바뀌지 않습니다.

## 플랫폼 비교

2026-10-30에 각 사의 공식 요금·문서 페이지에서 확인했습니다. 비용 중 "추정"은 공식 단가로 계산한 값입니다.

| 플랫폼 | MySQL | 한 달 비용 (앱 ~0.5–1GB + MySQL) | 꺼짐(슬립) | 한국에서 가까운 리전 |
| --- | --- | --- | --- | --- |
| **Railway** | 공식 `mysql` 이미지 템플릿 (관리형 아님, 백업 기능 있음) | Hobby $5(사용량 $5 포함), 추정 $8–12 | 기본 상시 구동 | 싱가포르 |
| Render | 관리형 MySQL 없음 (Postgres만) | 유료 인스턴스 2개 + 디스크, 추정 $14–32 | 무료 플랜은 15분 뒤 슬립, 기동 약 1분 | 싱가포르 |
| Fly.io | 관리형 없음, 볼륨에 직접 운영 | 1GB 머신 2대, 추정 약 $18 (신규 무료 티어 없음) | 자동 정지를 켜면 콜드스타트 | 도쿄, 싱가포르 |
| AWS Lightsail | 관리형 DB 1GB $15, 또는 VM 안에 compose | 2GB VM 1대에 compose로 $12 | 없음 | 서울 |
| AWS EC2 + RDS | RDS MySQL 8.4 | 서울 t4g.small + db.t4g.micro 추정 $34+ (신규 가입 크레딧 최대 $200, 6개월) | 없음 | 서울 |
| Oracle Always Free | VM 안에 직접 또는 HeatWave 무료 1개 | $0 | 없음. 단 7일간 사용률이 낮으면 유휴 인스턴스로 회수될 수 있음 | 미확인 |

| 프론트 | 무료 한도 | SPA fallback |
| --- | --- | --- |
| **Cloudflare Workers 정적 자산** | 정적 자산 요청 무료·무제한, 빌드 월 3,000분 | `not_found_handling: "single-page-application"` |
| Vercel Hobby | 전송 100GB/월. 비상업·개인용만 | `vercel.json`의 `rewrites` |
| Netlify Free | 월 300크레딧. 다 쓰면 다음 주기까지 사이트 정지 | `_redirects`에 `/* /index.html 200` |

고른 이유:

- **Railway**: Dockerfile을 그대로 빌드하고, MySQL을 같은 프로젝트 안 사설 네트워크로 붙일 수 있습니다. 상시 구동이라 JVM 콜드스타트(이 이미지는 로컬에서 기동에 약 15초)가 없습니다. 한 달 몇 달러로 끝납니다. 대신 리전이 싱가포르라 서울보다 왕복 지연이 있고, MySQL이 관리형이 아니라 백업을 직접 켜야 합니다.
- **Cloudflare Workers 정적 자산**: 무료 한도에 걸릴 일이 없고 상업 이용 제한도 없습니다. Cloudflare가 새 프로젝트에는 Pages 대신 Workers를 권합니다.
- 고정 요금과 서울 리전이 더 중요해지면 Lightsail 서울 2GB VM에 `compose.yaml`을 그대로 올리는 것이 다음 후보입니다. HTTPS(Caddy 등), 백업, OS 업데이트는 직접 해야 합니다.

출처: [Railway 요금](https://docs.railway.com/reference/pricing/plans) · [Railway 리전](https://docs.railway.com/reference/deployment-regions) · [Railway MySQL](https://docs.railway.com/guides/mysql) · [Render 요금](https://render.com/pricing) · [Render 무료 플랜](https://render.com/docs/free) · [Fly.io 요금](https://docs.fly.io/about/pricing) · [Lightsail 요금](https://aws.amazon.com/lightsail/pricing/) · [AWS 무료 플랜](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html) · [RDS MySQL 버전](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/MySQL.Concepts.VersionMgmt.html) · [Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) · [Cloudflare Workers 요금](https://developers.cloudflare.com/workers/platform/pricing/) · [Cloudflare SPA 설정](https://developers.cloudflare.com/workers/static-assets/routing/single-page-application/) · [Cloudflare Pages 안내](https://developers.cloudflare.com/pages/) · [Vercel Hobby](https://vercel.com/docs/plans/hobby) · [Vercel 공정 사용](https://vercel.com/docs/limits/fair-use-guidelines) · [Netlify 크레딧](https://docs.netlify.com/manage/accounts-and-billing/billing/billing-for-credit-based-plans/credit-based-pricing-plans/)

## 직접 해야 할 일 (순서대로)

계정 생성, 결제 수단 등록, 비밀값 입력은 사람이 해야 합니다. 대시보드 메뉴 이름은 바뀔 수 있습니다.

### 1. Railway: MySQL

1. [railway.com](https://railway.com)에 GitHub 계정으로 가입합니다. Trial($5, 30일)로 시작해 Hobby($5/월)로 올립니다.
2. 새 프로젝트를 만들고 **Database → MySQL**을 추가합니다. 서비스 이름은 `MySQL` 그대로 둡니다(아래 변수 참조에 쓰입니다).
3. MySQL 서비스 설정에서 리전을 싱가포르로, 이미지를 테스트와 같은 `mysql:8.4`로 맞추고, Backups를 켭니다.

### 2. Railway: 백엔드

1. 같은 프로젝트에 **GitHub Repo → MJU-MOTU/BACK**을 추가합니다. `railway.json`을 읽어 Dockerfile로 빌드하고, 배포할 때 `/actuator/health`가 200이어야 트래픽을 넘깁니다.
2. 리전을 MySQL과 같은 싱가포르로 둡니다.
3. **Variables**에 아래를 넣습니다. `${{MySQL.…}}`는 Railway의 다른 서비스 변수 참조 문법입니다.

| 이름 | 값 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}` |
| `DB_USERNAME` | `${{MySQL.MYSQLUSER}}` |
| `DB_PASSWORD` | `${{MySQL.MYSQLPASSWORD}}` |
| `JWT_SECRET` | `openssl rand -base64 48`로 만든 값. 어디에도 커밋하지 않습니다 |
| `JAVA_TOOL_OPTIONS` | `-Xmx256m` (아래 "메모리" 참고) |
| `CORS_ALLOWED_ORIGINS` | 4단계에서 정해지는 프론트 주소. 처음엔 비워 둡니다 |
| `KIS_ENABLED`, `KIS_APP_KEY`, `KIS_APP_SECRET` | 실시간 시세를 쓸 때만. 없으면 저장된 마지막 가격으로 동작합니다 |

`PORT`는 Railway가 넣어 주고, 서버는 그 포트로 뜹니다.

4. **Settings → Networking → Generate Domain**으로 공개 주소(`https://….up.railway.app`)를 만들고 `https://…/actuator/health`가 `{"status":"UP"}`인지 확인합니다. 첫 배포 때 Flyway가 테이블과 종목 28개를 만듭니다.

### 3. Cloudflare: 프론트엔드

1. [Cloudflare](https://dash.cloudflare.com)에 가입하고 Workers & Pages에서 Git 저장소 **MJU-MOTU/FRONT**를 연결합니다.
2. 빌드 명령 `npm run build`, 배포 명령 `npx wrangler deploy`(기본값)로 둡니다.
3. **Settings → Build → Build variables and secrets**에 `VITE_API_BASE_URL=https://….up.railway.app`(2단계 주소)를 넣고 배포합니다. 이 값은 빌드할 때 코드에 들어가므로 바꾸면 다시 배포해야 합니다.

### 4. 서로 연결

1. 프론트 주소(`https://motu-front.….workers.dev` 또는 연결한 도메인)를 Railway 백엔드의 `CORS_ALLOWED_ORIGINS`에 넣습니다. 여러 개면 쉼표로 구분합니다. 저장하면 다시 배포됩니다.
2. 프론트 주소에서 회원가입 → 종목 상세 → 매수 → 포트폴리오까지 확인합니다.

## 메모리

| 조건 | 결과 |
| --- | --- |
| 제한 없음, 기동 직후 | 약 380MB |
| 컨테이너 512MB 제한 + `-Xmx256m`, 요청 400건 뒤 | 336MB, 정상 |

- JVM 기본 최대 힙은 컨테이너 메모리의 25%입니다. 이미지는 `-XX:MaxRAMPercentage=50`으로 띄워 512MB 제한이면 힙 256MB를 씁니다.
- Railway처럼 사용량으로 과금하고 메모리 상한이 플랜 최대치로 잡히는 곳에서는 힙이 그만큼 커질 수 있습니다. 그래서 `JAVA_TOOL_OPTIONS=-Xmx256m`으로 직접 정합니다. `-Xmx`는 `MaxRAMPercentage`보다 우선합니다.

## 로컬에서 배포 이미지 확인

```bash
docker compose --profile app up -d --build --wait
curl localhost:8080/actuator/health
```

`back` 서비스는 `app` 프로필에만 있어서, 평소 `docker compose up -d`는 MySQL만 띄웁니다. CI도 같은 명령으로 이미지를 빌드해 헬스체크가 healthy가 되는지 봅니다.

이미지 구성:

- 빌드 단계(JDK 21)에서 `bootJar`를 만들고 계층별로 풀어, 실행 단계(JRE 21)에 의존성 → 애플리케이션 순서로 복사합니다. 코드만 바꾸면 다시 받을 층은 약 135KB입니다(의존성 층 약 62MB).
- 비루트 사용자 `motu`로 실행합니다.
- `HEALTHCHECK`는 `/actuator/health`(DB 연결 포함)를 봅니다. Railway는 이 설정 대신 `railway.json`의 `healthcheckPath`를 배포할 때만 확인합니다.

## 다른 플랫폼으로 옮길 때

- 서버는 `PORT`(기본 8080)로 뜨고, DB·비밀값은 모두 환경 변수입니다. 백엔드 README의 환경 변수 표가 전부입니다.
- Vercel에 프론트를 올린다면 `vercel.json`에 SPA fallback을 넣습니다.

```json
{ "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }] }
```
