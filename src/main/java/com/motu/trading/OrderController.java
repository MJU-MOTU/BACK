package com.motu.trading;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.motu.common.error.BusinessException;
import com.motu.common.error.ErrorCode;
import com.motu.common.security.CurrentUser;
import com.motu.market.Stock;
import com.motu.market.StockRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

	private final OrderService orderService;
	private final OrderRepository orderRepository;
	private final ExecutionRepository executionRepository;
	private final StockRepository stockRepository;

	OrderController(OrderService orderService, OrderRepository orderRepository,
			ExecutionRepository executionRepository, StockRepository stockRepository) {
		this.orderService = orderService;
		this.orderRepository = orderRepository;
		this.executionRepository = executionRepository;
		this.stockRepository = stockRepository;
	}

	// 가격은 받지 않는다. 체결 가격은 서버가 체결 시점에 정한다(R3)
	public record OrderRequest(
			@NotNull @Pattern(regexp = "^[0-9A-Z]{6}$") String stockCode,
			@NotNull Side side,
			@Min(1) @Max(1_000_000) int quantity) {
	}

	public record OrderSummary(Long orderId, String stockCode, String stockName, Side side, int quantity,
			Order.Status status, ErrorCode rejectReason, OrderResult.ExecutionView execution, Instant createdAt) {
	}

	public record OrderPage(List<OrderSummary> items, int page, int size, long totalElements, int totalPages) {
	}

	// 새로 체결하면 201, 같은 Idempotency-Key로 다시 와서 처음 결과를 돌려주면 200
	@PostMapping
	ResponseEntity<OrderResult> place(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody OrderRequest request) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		String key = idempotencyKey.strip();
		if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		OrderResult result = orderService.place(CurrentUser.id(jwt),
				new OrderCommand(request.stockCode(), request.side(), request.quantity(), key));
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@GetMapping
	@Transactional(readOnly = true)
	OrderPage history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		Page<Order> orders = orderRepository.findByUserIdOrderByIdDesc(CurrentUser.id(jwt),
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
		List<Long> orderIds = orders.map(Order::getId).getContent();
		Map<Long, Execution> executions = executionRepository.findByOrderIdIn(orderIds).stream()
			.collect(Collectors.toMap(Execution::getOrderId, Function.identity()));
		Map<String, String> names = stockRepository.findAllById(orders.map(Order::getStockCode).toSet()).stream()
			.collect(Collectors.toMap(Stock::getCode, Stock::getName));

		return new OrderPage(orders.map(order -> {
			Execution execution = executions.get(order.getId());
			return new OrderSummary(order.getId(), order.getStockCode(), names.get(order.getStockCode()),
					order.getSide(), order.getQuantity(), order.getStatus(), order.getRejectReason(),
					execution == null ? null : OrderResult.ExecutionView.from(execution), order.getCreatedAt());
		}).getContent(), orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages());
	}

}
