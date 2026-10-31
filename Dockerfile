# 배포용 이미지. 로컬에서 MySQL과 함께 띄우기: docker compose --profile app up -d --build

# 1) 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# 테스트는 CI(Testcontainers)에서 돌리므로 이미지 빌드에서는 뺀다
RUN ./gradlew --no-daemon bootJar -x test \
	&& java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination build/extracted

# 2) 실행: JRE만 담는다. 자주 바뀌는 층(application)을 마지막에 둬서 다시 받을 양을 줄인다
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system motu && useradd --system --gid motu --no-create-home motu
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER motu
EXPOSE 8080
# DB 연결까지 포함한 상태. 배포 플랫폼은 보통 자기 헬스체크 설정(같은 경로)을 따로 쓴다
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --start-interval=5s --retries=3 \
	CMD curl -fsS "http://localhost:${PORT:-8080}/actuator/health" > /dev/null || exit 1
# JVM 기본 최대 힙은 컨테이너 메모리의 25%라서 512MB면 128MB다. 50%면 512MB 제한에서 힙 256MB, 전체 약 340MB(측정값)
# 메모리 제한이 플랜 최대치로 잡히는 플랫폼(사용량 과금)은 JAVA_TOOL_OPTIONS=-Xmx256m처럼 힙을 직접 정한다
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]
