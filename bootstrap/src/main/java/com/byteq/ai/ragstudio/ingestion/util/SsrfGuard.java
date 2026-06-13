package com.byteq.ai.ragstudio.ingestion.util;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护工具类
 * <p>
 * 用于校验 HTTP 请求目标 URL 的安全性，防止服务端请求伪造（SSRF）攻击。
 * 主要防护策略：
 * <ul>
 *   <li>仅允许 HTTP/HTTPS 协议</li>
 *   <li>通过 DNS 解析主机名，拒绝解析到私有/保留 IP 地址段的请求</li>
 * </ul>
 * </p>
 * <p>
 * 被拒绝的 IP 地址段包括：
 * <ul>
 *   <li>{@code 10.0.0.0/8} - A 类私有网络</li>
 *   <li>{@code 172.16.0.0/12} - B 类私有网络</li>
 *   <li>{@code 192.168.0.0/16} - C 类私有网络</li>
 *   <li>{@code 127.0.0.0/8} - 本地回环地址</li>
 *   <li>{@code 169.254.0.0/16} - 链路本地地址（含云元数据服务）</li>
 *   <li>{@code 0.0.0.0/8} - 当前网络</li>
 *   <li>IPv6 回环地址 ({@code ::1}) 和站点本地地址</li>
 * </ul>
 * </p>
 */
public final class SsrfGuard {

    private SsrfGuard() {
        // 工具类，禁止实例化
    }

    /**
     * 校验 URL 是否存在 SSRF 风险
     * <p>
     * 对传入的 URL 进行解析，验证协议类型和主机 IP 地址。
     * 如果 URL 不安全，将抛出 {@link ServiceException}。
     * </p>
     *
     * @param url 待校验的 URL 字符串
     * @throws ServiceException 如果 URL 的协议不合法或目标 IP 属于私有/保留地址段
     */
    public static void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new ServiceException("无效的URL格式: " + url);
        }

        // 仅允许 HTTP/HTTPS 协议
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new ServiceException("仅允许 HTTP/HTTPS 协议，拒绝: " + scheme + "，URL: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ServiceException("URL中缺少主机名: " + url);
        }

        // DNS 解析主机名，检查所有解析到的 IP 地址
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ServiceException("无法解析主机名: " + host);
        }

        for (InetAddress addr : addresses) {
            if (isPrivateOrReserved(addr)) {
                throw new ServiceException(
                        "SSRF防护: 目标主机 " + host + " 解析到私有/保留IP地址 " + addr.getHostAddress()
                                + "，请求被拒绝");
            }
        }
    }

    /**
     * 判断 IP 地址是否属于私有或保留地址段
     *
     * @param addr IP 地址
     * @return true 如果地址是私有或保留的
     */
    private static boolean isPrivateOrReserved(InetAddress addr) {
        // JDK 内置检查：回环地址 (127.x.x.x / ::1)、链路本地地址 (169.254.x.x)、
        // 站点本地地址 (10.x, 172.16-31.x, 192.168.x / fec0::)
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }

        // 额外检查：JDK 的 isSiteLocalAddress 在某些实现中可能不覆盖所有情况，
        // 对 IPv4 进行显式检查以确保安全
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 0.0.0.0/8 - 当前网络
            if (b0 == 0) {
                return true;
            }
            // 10.0.0.0/8
            if (b0 == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            // 127.0.0.0/8 (loopback, explicit check)
            if (b0 == 127) {
                return true;
            }
            // 169.254.0.0/16 (link-local, explicit check)
            if (b0 == 169 && b1 == 254) {
                return true;
            }
        }

        // IPv6 回环地址
        if (addr.isLoopbackAddress()) {
            return true;
        }

        return false;
    }
}
