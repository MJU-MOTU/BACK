package com.motu.wallet;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final Clock clock;
	private final long initialCash;

	WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository, Clock clock,
			@Value("${motu.wallet.initial-cash}") long initialCash) {
		this.walletRepository = walletRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.clock = clock;
		this.initialCash = initialCash;
	}

	// 가입 트랜잭션 안에서 부른다. 지갑과 첫 원장 줄을 함께 만든다
	@Transactional(propagation = Propagation.MANDATORY)
	public Wallet open(Long userId) {
		Instant now = clock.instant();
		Wallet wallet = walletRepository.save(Wallet.open(userId, initialCash, now));
		ledgerEntryRepository.save(
				new LedgerEntry(wallet.getId(), LedgerEntry.Type.INITIAL, initialCash, initialCash, null, now));
		return wallet;
	}

	// 지갑 잠금을 잡은 주문 트랜잭션 안에서만 부른다
	@Transactional(propagation = Propagation.MANDATORY)
	public void withdrawForBuy(Wallet wallet, long amount, Long orderId, Instant now) {
		wallet.withdraw(amount, now);
		ledgerEntryRepository.save(
				new LedgerEntry(wallet.getId(), LedgerEntry.Type.BUY, -amount, wallet.getCash(), orderId, now));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void depositForSell(Wallet wallet, long amount, Long orderId, Instant now) {
		wallet.deposit(amount, now);
		ledgerEntryRepository.save(
				new LedgerEntry(wallet.getId(), LedgerEntry.Type.SELL, amount, wallet.getCash(), orderId, now));
	}

}
