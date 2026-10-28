package com.platform.common.security;

import com.platform.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 外部地址安全策略（需求 3.26 SSRF）：仅允许 http/https；生产可关闭内网访问并配置域名白名单。
 * 默认 allow-private=true 兼容本地开发（Mock/局域网 MCP），生产务必设置为 false。
 */
@Service
public class NetworkPolicyService {

    @Value("${security.network.allow-private:true}")
    private boolean allowPrivate;

    @Value("${security.network.allowed-hosts:}")
    private String allowedHosts;

    public void validateHttpUrl(String url, String label) {
        validateHttpUrl(url, label, allowPrivate);
    }

    /** 用户可控 URL（文档导入等）必须禁止内网/环回，即使开发环境全局 allow-private=true。 */
    public void validatePublicHttpUrl(String url, String label) {
        validateHttpUrl(url, label, false);
    }

    private void validateHttpUrl(String url, String label, boolean allowPrivate) {
        if (url == null || url.isBlank()) throw new BusinessException(400, label + "不能为空");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new BusinessException(400, label + "格式错误");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException(400, label + "仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new BusinessException(400, label + "缺少主机名");

        List<String> allow = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        if (!allow.isEmpty()) {
            boolean matched = allow.stream().anyMatch(h ->
                    host.equalsIgnoreCase(h) || host.toLowerCase().endsWith("." + h.toLowerCase()));
            if (!matched) throw new BusinessException(400, label + "不在允许的域名白名单内：" + host);
        }
        if (allowPrivate) return;

        resolvePublicHost(host);
    }

    /** URL 抓取器在真正建立连接前调用的 DNS 钉扎：所有解析结果必须是公网地址。 */
    public List<InetAddress> resolvePublicHost(String host) {
        if (host == null || host.isBlank()) throw new BusinessException(400, "缺少主机名");
        try {
            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
            if (addresses.isEmpty()) throw new BusinessException(400, "域名解析为空：" + host);
            for (InetAddress address : addresses) {
                if (isPrivate(address)) {
                    throw new BusinessException(400, "不允许访问内网/环回/链路本地地址：" + host);
                }
            }
            return addresses;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "域名解析失败：" + host);
        }
    }

    private boolean isPrivate(InetAddress a) {
        return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()
                || a.getHostAddress().startsWith("169.254.")
                || a.getHostAddress().startsWith("100.64.")
                || a.getHostAddress().startsWith("fc")
                || a.getHostAddress().startsWith("fd")
                || a.getHostAddress().startsWith("fe80");
    }
}
