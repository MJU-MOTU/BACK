package com.motu.trading;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, Long> {

	Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

	Page<Order> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

}

interface ExecutionRepository extends JpaRepository<Execution, Long> {

	Optional<Execution> findByOrderId(Long orderId);

	List<Execution> findByOrderIdIn(List<Long> orderIds);

}

interface HoldingRepository extends JpaRepository<Holding, Long> {

	Optional<Holding> findByUserIdAndStockCode(Long userId, String stockCode);

	List<Holding> findByUserIdOrderByStockCode(Long userId);

}
