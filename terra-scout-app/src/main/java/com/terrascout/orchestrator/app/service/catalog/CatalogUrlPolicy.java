package com.terrascout.orchestrator.app.service.catalog;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * SDK 元数据下载地址白名单（security.md 6.6 官方源域名清单）。
 *
 * <p>官方源响应与本地元数据文件中的下载地址均为外部输入，入库存前必须同时满足：
 * <ol>
 *   <li>协议为 {@code https}；</li>
 *   <li>主机名精确命中官方源 / 官方镜像域名（大小写不敏感）。</li>
 * </ol>
 * 校验失败一律拒绝，防下载地址被上游劫持指向恶意主机。
 */
public final class CatalogUrlPolicy {

    /** 允许的主机名集合：四语言官方源与华为/淘宝 npm 镜像兜底。 */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.adoptium.net",
            "adoptium.net",
            "github.com",
            "objects.githubusercontent.com",
            "go.dev",
            "golang.google.cn",
            "dl.google.com",
            "api.github.com",
            "nodejs.org",
            "registry.npmmirror.com",
            "python.org",
            "www.python.org",
            "repo.huaweicloud.com");

    private CatalogUrlPolicy() {
    }

    /** 校验下载地址是否 https 且主机在白名单内（null / 非法 URI 一律拒绝）。 */
    public static boolean isAllowed(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
