package com.motu.trading;

public record OrderCommand(String stockCode, Side side, int quantity, String idempotencyKey) {
}
