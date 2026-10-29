package com.motu.wallet;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	Page<LedgerEntry> findByWalletIdOrderByIdDesc(Long walletId, Pageable pageable);

	Optional<LedgerEntry> findByOrderId(Long orderId);

	@Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.walletId = :walletId")
	long sumAmountByWalletId(@Param("walletId") Long walletId);

}
