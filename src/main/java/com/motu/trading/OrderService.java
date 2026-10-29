package com.motu.trading;

import java.util.Optional;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.market.Quote;
import com.motu.market.QuoteService;
import com.motu.market.StockRepository;

import org.springframework.stereotype.Service;

// 트랜잭션을 일부러 걸지 않는다. 단계마다 따로 트랜잭션을 쓴다:
// 1) 이미 처리한 키인지 확인  2) 시세 조회(KIS HTTP)  3) 지갑을 잠그고 체결(OrderExecutor)
// 시세를 기다리는 동안 지갑을 잠그지 않고, 체결 트랜잭션이 잠금보다 먼저 찍힌 스냅샷을 보지 않게 하려는 것이다
@Service
public class OrderService {

	private final StockRepository stockRepository;
	private final QuoteService quoteService;
	private final OrderReplays orderReplays;
	private final OrderExecutor orderExecutor;

	OrderService(StockRepository stockRepository, QuoteService quoteService, OrderReplays orderReplays,
			OrderExecutor orderExecutor) {
		this.stockRepository = stockRepository;
		this.quoteService = quoteService;
		this.orderReplays = orderReplays;
		this.orderExecutor = orderExecutor;
	}

	public OrderResult place(Long userId, OrderCommand command) {
		Optional<OrderResult> replay = orderReplays.find(userId, command);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (!stockRepository.existsById(command.stockCode())) {
			throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
		}
		// 장이 끝났거나 KIS가 실패하면 마지막 가격(STORED)으로 체결한다(설계 D2). 그것도 없으면 503
		Quote quote = quoteService.get(command.stockCode());
		return orderExecutor.execute(userId, command, quote);
	}

}
