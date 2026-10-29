-- 调用方 Key IP 白名单（逗号/换行分隔，支持 IPv4/IPv6 精确地址和 CIDR）；空=不限制。
ALTER TABLE gateway_api_key
    ADD COLUMN allowed_ips TEXT NULL AFTER allowed_paths;
