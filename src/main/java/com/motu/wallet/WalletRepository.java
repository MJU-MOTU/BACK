package com.motu.wallet;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	Optional<Wallet> findByUserId(Long userId);

	// SELECT ... FOR UPDATE. 주문은 모두 이 잠금을 먼저 잡으므로 한 회원의 주문은 한 줄로 처리된다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select w from Wallet w where w.userId = :userId")
	Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);

}
