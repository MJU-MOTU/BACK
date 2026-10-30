package com.motu.trading;

import java.util.Optional;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.Quote;
import com.motu.market.QuoteService;
import com.motu.market.StockRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 지갑을 잠그기 전 단계(같은 키 확인 → 종목 확인 → 시세)를 트랜잭션 하나로 묶는다.
// 따로 열면 주문 1건에 트랜잭션이 4개가 되고, 그만큼 커넥션을 빌리고 BEGIN·COMMIT·SET을 주고받는다
@Component
class OrderPreparation {

	private final OrderReplays orderReplays;
	private final StockRepository stockRepository;
	private final QuoteService quoteService;

	OrderPreparation(OrderReplays orderReplays, StockRepository stockRepository, QuoteService quoteService) {
		this.orderReplays = orderReplays;
		this.stockRepository = stockRepository;
		this.quoteService = quoteService;
	}

	// 이미 처리한 키면 처음 결과, 아니면 체결에 쓸 시세. 시세 조회가 마지막 가격을 저장(쓰기)하므로 읽기 전용이 아니다
	@Transactional(noRollbackFor = BusinessException.class)
	Prepared prepare(Long userId, OrderCommand command) {
		Optional<OrderResult> replay = orderReplays.find(userId, command);
		if (replay.isPresent()) {
			return new Prepared(replay.get(), null);
		}
		if (!stockRepository.existsById(command.stockCode())) {
			throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
		}
		// 장이 끝났거나 KIS가 실패하면 마지막 가격(STORED)으로 체결한다(설계 D2). 그것도 없으면 503
		return new Prepared(null, quoteService.get(command.stockCode()));
	}

	record Prepared(OrderResult replay, Quote quote) {
	}

}
