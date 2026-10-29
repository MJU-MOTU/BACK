package com.motu.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.motu.IntegrationTest;
import com.motu.market.StockPrice;
import com.motu.market.StockPriceRepository;
import com.motu.support.TestUsers;
import com.motu.wallet.LedgerEntryRepository;
import com.motu.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트에서는 KIS가 꺼져 있어 저장된 가격(STORED)으로 체결된다. 가격은 테스트가 직접 넣는다
@IntegrationTest
class OrderApiTests {

	static final String SAMSUNG = "005930";
	static final String HYUNDAI = "005380";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestUsers testUsers;

	@Autowired
	StockPriceRepository stockPriceRepository;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	LedgerEntryRepository ledgerEntryRepository;

	Long userId;

	@BeforeEach
	void setUp() {
		userId = testUsers.create();
		setPrice(SAMSUNG, 70_000);
		setPrice(HYUNDAI, 250_000);
	}

	@Test
	void 가입하면_가상_현금_1000만원이_든_지갑과_첫_원장이_생긴다() throws Exception {
		mockMvc.perform(get("/api/v1/wallet").with(as(userId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cash").value(10_000_000));
		mockMvc.perform(get("/api/v1/wallet/ledger").with(as(userId)))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].type").value("INITIAL"))
			.andExpect(jsonPath("$.items[0].amount").value(10_000_000));
	}

	@Test
	void 매수하면_잔고가_줄고_보유가_생기며_원장_합계가_잔고와_같다() throws Exception {
		order("BUY", SAMSUNG, 10)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("FILLED"))
			.andExpect(jsonPath("$.execution.price").value(70_000))
			.andExpect(jsonPath("$.execution.amount").value(700_000))
			.andExpect(jsonPath("$.execution.priceSource").value("STORED"))
			.andExpect(jsonPath("$.cashAfter").value(9_300_000));

		mockMvc.perform(get("/api/v1/portfolio").with(as(userId)))
			.andExpect(jsonPath("$.holdings[0].stockCode").value(SAMSUNG))
			.andExpect(jsonPath("$.holdings[0].quantity").value(10))
			.andExpect(jsonPath("$.holdings[0].averagePrice").value(70_000.00));
		assertLedgerMatchesWallet();
	}

	@Test
	void 잔고가_부족하면_422이고_잔고는_그대로이며_거절된_주문이_남는다() throws Exception {
		order("BUY", HYUNDAI, 41)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH"));

		assertThat(cash()).isEqualTo(10_000_000);
		mockMvc.perform(get("/api/v1/orders").with(as(userId)))
			.andExpect(jsonPath("$.items[0].status").value("REJECTED"))
			.andExpect(jsonPath("$.items[0].rejectReason").value("INSUFFICIENT_CASH"));
	}

	@Test
	void 매도하면_판_수량만큼의_원가로_실현손익을_계산하고_다_팔면_보유가_사라진다() throws Exception {
		order("BUY", SAMSUNG, 10).andExpect(status().isCreated());
		setPrice(SAMSUNG, 80_000);

		// 원가 700,000 × 4/10 = 280,000, 매도 320,000 → 실현손익 40,000
		order("SELL", SAMSUNG, 4)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.execution.realizedPnl").value(40_000));
		mockMvc.perform(get("/api/v1/portfolio").with(as(userId)))
			.andExpect(jsonPath("$.holdings[0].quantity").value(6))
			.andExpect(jsonPath("$.holdings[0].totalCost").value(420_000))
			.andExpect(jsonPath("$.holdings[0].unrealizedPnl").value(60_000));

		order("SELL", SAMSUNG, 6)
			.andExpect(jsonPath("$.execution.realizedPnl").value(60_000));
		mockMvc.perform(get("/api/v1/portfolio").with(as(userId)))
			.andExpect(jsonPath("$.holdings.length()").value(0))
			.andExpect(jsonPath("$.cash").value(10_100_000));
		assertLedgerMatchesWallet();
	}

	@Test
	void 보유보다_많이_팔면_422_INSUFFICIENT_QUANTITY다() throws Exception {
		order("BUY", SAMSUNG, 3);

		order("SELL", SAMSUNG, 4)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_QUANTITY"));
		order("SELL", HYUNDAI, 1)
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_QUANTITY"));
	}

	@Test
	void 같은_키로_다시_보내면_새로_체결하지_않고_200으로_처음_결과를_준다() throws Exception {
		String key = UUID.randomUUID().toString();
		String first = order("BUY", SAMSUNG, 1, key).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		order("BUY", SAMSUNG, 1, key)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value((Integer) JsonPath.read(first, "$.orderId")))
			.andExpect(jsonPath("$.replayed").value(true))
			.andExpect(jsonPath("$.cashAfter").value(9_930_000));
		assertThat(cash()).isEqualTo(9_930_000);
	}

	@Test
	void 같은_키로_다른_주문을_보내면_409이고_거절된_주문을_다시_보내면_같은_거절이다() throws Exception {
		String key = UUID.randomUUID().toString();
		order("BUY", SAMSUNG, 1, key);
		order("BUY", SAMSUNG, 2, key)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

		String rejectedKey = UUID.randomUUID().toString();
		order("SELL", HYUNDAI, 1, rejectedKey).andExpect(jsonPath("$.code").value("INSUFFICIENT_QUANTITY"));
		order("BUY", HYUNDAI, 1);
		// 그 사이 보유가 생겼어도 같은 키는 처음 결과(거절)를 그대로 돌려준다
		order("SELL", HYUNDAI, 1, rejectedKey).andExpect(jsonPath("$.code").value("INSUFFICIENT_QUANTITY"));
	}

	@Test
	void 요청이_잘못됐거나_시세가_없으면_주문을_만들지_않는다() throws Exception {
		mockMvc.perform(orderRequest("BUY", SAMSUNG, 1).with(as(userId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		order("BUY", SAMSUNG, 0).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		order("HOLD", SAMSUNG, 1).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		order("BUY", "999999", 1)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
		// 카카오(035720)는 가격을 받은 적이 없다
		order("BUY", "035720", 1)
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("PRICE_UNAVAILABLE"));

		mockMvc.perform(get("/api/v1/orders").with(as(userId)))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void 포트폴리오는_평균단가를_소수_둘째_자리까지_보여주고_총자산을_계산한다() throws Exception {
		order("BUY", SAMSUNG, 1);
		setPrice(SAMSUNG, 70_001);
		order("BUY", SAMSUNG, 2);
		order("BUY", HYUNDAI, 2);
		setPrice(HYUNDAI, 260_000);

		// 삼성: 원가 210,002 / 3주 = 70,000.67, 평가 210,003 / 현대: 원가 500,000, 평가 520,000
		mockMvc.perform(get("/api/v1/portfolio").with(as(userId)))
			.andExpect(jsonPath("$.holdings[?(@.stockCode=='005930')].averagePrice").value(70_000.67))
			.andExpect(jsonPath("$.totalCost").value(710_002))
			.andExpect(jsonPath("$.totalEvaluation").value(730_003))
			.andExpect(jsonPath("$.totalAsset").value(10_000_000 - 710_002 + 730_003))
			.andExpect(jsonPath("$.totalUnrealizedPnl").value(20_001))
			.andExpect(jsonPath("$.partial").value(false));
	}

	private ResultActions order(String side, String code, int quantity) throws Exception {
		return order(side, code, quantity, UUID.randomUUID().toString());
	}

	private ResultActions order(String side, String code, int quantity, String key) throws Exception {
		return mockMvc.perform(orderRequest(side, code, quantity).header("Idempotency-Key", key).with(as(userId)));
	}

	private static MockHttpServletRequestBuilder orderRequest(String side, String code, int quantity) {
		return post("/api/v1/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"stockCode":"%s","side":"%s","quantity":%d}""".formatted(code, side, quantity));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor as(Long userId) {
		return jwt().jwt(token -> token.subject(userId.toString()));
	}

	private void setPrice(String code, long price) {
		StockPrice stockPrice = stockPriceRepository.findById(code)
			.orElseGet(() -> new StockPrice(code, price, 0, BigDecimal.ZERO, Instant.now()));
		stockPrice.update(price, 0, BigDecimal.ZERO, Instant.now());
		stockPriceRepository.save(stockPrice);
	}

	private long cash() {
		return walletRepository.findByUserId(userId).orElseThrow().getCash();
	}

	private void assertLedgerMatchesWallet() {
		Long walletId = walletRepository.findByUserId(userId).orElseThrow().getId();
		assertThat(ledgerEntryRepository.sumAmountByWalletId(walletId)).isEqualTo(cash());
	}

}
