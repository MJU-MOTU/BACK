CREATE TABLE stocks
(
    code   VARCHAR(6)   NOT NULL,
    name   VARCHAR(100) NOT NULL,
    market VARCHAR(10)  NOT NULL,
    PRIMARY KEY (code)
);

-- KIS 조회가 실패할 때 보여 줄 마지막 가격. 실제로 받아 온 값만 저장한다(가짜 가격을 만들지 않는다)
CREATE TABLE stock_prices
(
    stock_code    VARCHAR(6)    NOT NULL,
    price         BIGINT        NOT NULL,
    change_amount BIGINT        NOT NULL,
    change_rate   DECIMAL(7, 2) NOT NULL,
    as_of         DATETIME(6)   NOT NULL,
    PRIMARY KEY (stock_code),
    CONSTRAINT fk_stock_prices_stock FOREIGN KEY (stock_code) REFERENCES stocks (code)
);

-- MVP용 초기 종목. 전체 종목 마스터는 KRX/KIS 종목 파일로 따로 적재한다
INSERT INTO stocks (code, name, market)
VALUES ('005930', '삼성전자', 'KOSPI'),
       ('000660', 'SK하이닉스', 'KOSPI'),
       ('373220', 'LG에너지솔루션', 'KOSPI'),
       ('207940', '삼성바이오로직스', 'KOSPI'),
       ('005380', '현대차', 'KOSPI'),
       ('000270', '기아', 'KOSPI'),
       ('068270', '셀트리온', 'KOSPI'),
       ('035420', 'NAVER', 'KOSPI'),
       ('035720', '카카오', 'KOSPI'),
       ('051910', 'LG화학', 'KOSPI'),
       ('006400', '삼성SDI', 'KOSPI'),
       ('105560', 'KB금융', 'KOSPI'),
       ('055550', '신한지주', 'KOSPI'),
       ('005490', 'POSCO홀딩스', 'KOSPI'),
       ('012330', '현대모비스', 'KOSPI'),
       ('028260', '삼성물산', 'KOSPI'),
       ('066570', 'LG전자', 'KOSPI'),
       ('003550', 'LG', 'KOSPI'),
       ('017670', 'SK텔레콤', 'KOSPI'),
       ('030200', 'KT', 'KOSPI'),
       ('247540', '에코프로비엠', 'KOSDAQ'),
       ('086520', '에코프로', 'KOSDAQ'),
       ('196170', '알테오젠', 'KOSDAQ'),
       ('028300', 'HLB', 'KOSDAQ'),
       ('263750', '펄어비스', 'KOSDAQ'),
       ('293490', '카카오게임즈', 'KOSDAQ'),
       ('035900', 'JYP Ent.', 'KOSDAQ'),
       ('041510', '에스엠', 'KOSDAQ');
