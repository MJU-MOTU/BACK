package com.motu.wallet;

import java.time.Instant;
import java.util.List;

import com.motu.common.security.CurrentUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	WalletController(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
		this.walletRepository = walletRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
	}

	public record WalletResponse(long cash, Instant updatedAt) {
	}

	public record LedgerResponse(Long id, LedgerEntry.Type type, long amount, long balanceAfter, Long orderId,
			Instant createdAt) {
	}

	public record LedgerPage(List<LedgerResponse> items, int page, int size, long totalElements, int totalPages) {
	}

	@GetMapping
	@Transactional(readOnly = true)
	WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
		Wallet wallet = find(jwt);
		return new WalletResponse(wallet.getCash(), wallet.getUpdatedAt());
	}

	@GetMapping("/ledger")
	@Transactional(readOnly = true)
	LedgerPage ledger(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		Wallet wallet = find(jwt);
		Page<LedgerEntry> entries = ledgerEntryRepository.findByWalletIdOrderByIdDesc(wallet.getId(),
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
		return new LedgerPage(entries.map(e -> new LedgerResponse(e.getId(), e.getType(), e.getAmount(),
				e.getBalanceAfter(), e.getOrderId(), e.getCreatedAt())).getContent(), entries.getNumber(),
				entries.getSize(), entries.getTotalElements(), entries.getTotalPages());
	}

	private Wallet find(Jwt jwt) {
		return walletRepository.findByUserId(CurrentUser.id(jwt))
			.orElseThrow(() -> new IllegalStateException("지갑이 없는 회원입니다"));
	}

}
