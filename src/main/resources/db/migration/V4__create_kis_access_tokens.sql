-- KIS 접근 토큰. 서버(Lambda 실행 환경)가 여러 개여도 토큰 하나를 같이 쓰고, 발급은 한 곳에서만 한다.
-- KIS는 토큰 발급 요청이 잦으면 막는다. 행은 하나(id = 1)만 두고 SELECT ... FOR UPDATE로 발급 순서를 정한다
CREATE TABLE kis_access_tokens
(
    id           TINYINT       NOT NULL,
    access_token VARCHAR(2048) NULL,
    -- 이 시각이 지나면 새로 발급한다(만료 1시간 전)
    refresh_at   DATETIME(6)   NULL,
    -- 발급이 실패하면 이 시각까지는 어느 서버도 다시 요청하지 않는다
    retry_after  DATETIME(6)   NULL,
    updated_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO kis_access_tokens (id, updated_at)
VALUES (1, UTC_TIMESTAMP(6));
