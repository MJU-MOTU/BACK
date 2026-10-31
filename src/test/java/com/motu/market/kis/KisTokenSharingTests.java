package com.motu.market.kis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.motu.IntegrationTest;
import com.motu.market.PriceSource.LiveQuote;
import com.motu.support.MutableClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Lambda에서는 실행 환경마다 KisClient가 따로 뜬다. 여러 대가 같은 DB를 보며 토큰을 같이 쓰는지 확인한다
@IntegrationTest
class KisTokenSharingTests {

	private static final String TOKEN = """
		{"access_token":"tkn-1","token_type":"Bearer","expires_in":86400}""";
	private static final String PRICE = """
		{"rt_cd":"0","msg1":"정상처리 되었습니다.","output":{"stck_prpr":"71200","prdy_vrss":"-800","prdy_ctrt":"-1.11"}}""";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T01:00:00Z"));
	final AtomicInteger tokenCalls = new AtomicInteger();
	volatile boolean tokenFails;
	HttpServer kis;

	@BeforeEach
	void setUp() throws IOException {
		// 커넥션이 auto-commit=false라 트랜잭션 안에서 지워야 커밋된다
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbcTemplate.update(
				"UPDATE kis_access_tokens SET access_token = NULL, refresh_at = NULL, retry_after = NULL WHERE id = 1"));
		kis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		kis.setExecutor(Executors.newFixedThreadPool(8));
		kis.createContext("/oauth2/tokenP", exchange -> {
			tokenCalls.incrementAndGet();
			// 발급이 끝나기 전에 다른 서버들이 도착하도록 조금 늦게 답한다
			sleep(Duration.ofMillis(200));
			respond(exchange, tokenFails ? 500 : 200, tokenFails ? "{}" : TOKEN);
		});
		kis.createContext("/uapi/domestic-stock/v1/quotations/inquire-price", exchange -> respond(exchange, 200, PRICE));
		kis.start();
	}

	@AfterEach
	void tearDown() {
		kis.stop(0);
	}

	@Test
	void 다른_서버가_받아_둔_토큰을_같이_쓴다() {
		assertThat(newServer().currentPrice("005930")).isPresent();
		assertThat(newServer().currentPrice("005930")).isPresent();

		assertThat(tokenCalls).hasValue(1);
	}

	@Test
	void 여러_서버가_동시에_처음_요청해도_토큰은_한_번만_발급한다() throws Exception {
		List<KisClient> servers = IntStream.range(0, 8).mapToObj(i -> newServer()).toList();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(servers.size());
		try {
			List<Future<Optional<LiveQuote>>> results = servers.stream().map(server -> pool.submit(() -> {
				start.await();
				return server.currentPrice("005930");
			})).toList();
			start.countDown();
			for (Future<Optional<LiveQuote>> result : results) {
				assertThat(result.get(30, TimeUnit.SECONDS)).isPresent();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(tokenCalls).hasValue(1);
	}

	@Test
	void 발급이_실패하면_다른_서버도_1분_동안_다시_요청하지_않는다() {
		tokenFails = true;
		assertThat(newServer().currentPrice("005930")).isEmpty();
		clock.advance(KisClient.TOKEN_RETRY_BACKOFF.minusSeconds(1));
		assertThat(newServer().currentPrice("005930")).isEmpty();
		assertThat(tokenCalls).hasValue(1);

		tokenFails = false;
		clock.advance(Duration.ofSeconds(1));
		assertThat(newServer().currentPrice("005930")).isPresent();
		assertThat(tokenCalls).hasValue(2);
	}

	// 서버(Lambda 실행 환경) 한 대. 메모리는 따로, DB는 같이 쓴다
	private KisClient newServer() {
		String baseUrl = "http://127.0.0.1:" + kis.getAddress().getPort();
		return new KisClient(RestClient.builder(), new KisProperties(true, baseUrl, "key", "secret"), clock,
				new JdbcKisTokenStore(jdbcTemplate, transactionManager));
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
