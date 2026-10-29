-- 请求签名/防重放：签名密钥使用 CryptoService 加密存储；空=关闭。
ALTER TABLE gateway_route
    ADD COLUMN signing_secret VARCHAR(512) NULL AFTER upstream_weights;
