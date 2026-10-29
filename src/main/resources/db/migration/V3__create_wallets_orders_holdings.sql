CREATE TABLE wallets
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    cash       BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallets_user UNIQUE (user_id),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- 애플리케이션 검사가 뚫려도 DB가 마지막으로 막는다
    CONSTRAINT chk_wallets_cash CHECK (cash >= 0)
);

-- 같은 회원이 같은 Idempotency-Key로 다시 보내면 새 주문을 만들지 않는다
CREATE TABLE orders
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    stock_code      VARCHAR(6)  NOT NULL,
    side            VARCHAR(4)  NOT NULL,
    quantity        INT         NOT NULL,
    status          VARCHAR(10) NOT NULL,
    reject_reason   VARCHAR(40) NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_user_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_stock FOREIGN KEY (stock_code) REFERENCES stocks (code),
    CONSTRAINT chk_orders_quantity CHECK (quantity >= 1)
);

CREATE INDEX idx_orders_user_created ON orders (user_id, created_at);

-- 시장가 주문은 주문 1건에 체결 1건이다. 지정가·분할 체결을 넣을 때를 위해 주문과 나눈다
CREATE TABLE executions
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    price        BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    amount       BIGINT      NOT NULL,
    price_source VARCHAR(10) NOT NULL,
    realized_pnl BIGINT      NULL,
    executed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_executions_order UNIQUE (order_id),
    CONSTRAINT fk_executions_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- 평균단가는 소수라 반올림 오차가 쌓이므로 저장하지 않고 총매입금액으로 계산한다
CREATE TABLE holdings
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    stock_code VARCHAR(6)  NOT NULL,
    quantity   INT         NOT NULL,
    total_cost BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_holdings_user_stock UNIQUE (user_id, stock_code),
    CONSTRAINT fk_holdings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_holdings_stock FOREIGN KEY (stock_code) REFERENCES stocks (code),
    CONSTRAINT chk_holdings_quantity CHECK (quantity > 0),
    CONSTRAINT chk_holdings_total_cost CHECK (total_cost >= 0)
);

-- 지갑 잔고가 왜 이 값인지 남기는 원장. 수정·삭제하지 않는다. 원장 합계 = 지갑 잔고
CREATE TABLE wallet_ledger
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    wallet_id     BIGINT      NOT NULL,
    type          VARCHAR(10) NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    order_id      BIGINT      NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_wallet_ledger_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_wallet_ledger_wallet_created ON wallet_ledger (wallet_id, created_at);

-- 이 마이그레이션 전에 가입한 회원에게도 지갑을 만든다 (초기 가상 현금 1,000만 원)
INSERT INTO wallets (user_id, cash, updated_at)
SELECT id, 10000000, NOW(6)
FROM users;

INSERT INTO wallet_ledger (wallet_id, type, amount, balance_after, order_id, created_at)
SELECT id, 'INITIAL', cash, cash, NULL, NOW(6)
FROM wallets;
