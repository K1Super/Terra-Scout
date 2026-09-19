package com.terrascout.orchestrator.app.unit;

import com.terrascout.orchestrator.app.security.TokenValidator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 校验器单测：常量时间比较 + 免鉴权白名单。
 */
class TokenValidatorTest {

    private static final String TOKEN = "VG9rZW4xMjM0NTY3ODkwMTIzNDU2Nzg5"; // base64

    @Test
    void validTokenAccepted() {
        assertThat(new TokenValidator(TOKEN).isTokenValid(TOKEN)).isTrue();
    }

    @Test
    void wrongTokenRejected() {
        assertThat(new TokenValidator(TOKEN).isTokenValid("other")).isFalse();
    }

    @Test
    void nullProvidedTokenRejected() {
        assertThat(new TokenValidator(TOKEN).isTokenValid(null)).isFalse();
    }

    @Test
    void emptyExpectedTokenAlwaysFails() {
        // 未配置 Token 时（terrascout.token 缺省）不应误放行任何请求
        assertThat(new TokenValidator("").isTokenValid("anything")).isFalse();
    }

    @Test
    void emptyVsNonEmptyDoesNotCompareEqual() {
        assertThat(new TokenValidator(TOKEN).isTokenValid("")).isFalse();
    }

    @Test
    void whitelistOnlyHealthGet() {
        TokenValidator v = new TokenValidator(TOKEN);
        assertThat(v.isWhitelisted("GET", "/api/v1/health")).isTrue();
        assertThat(v.isWhitelisted("POST", "/api/v1/health")).isFalse();
        assertThat(v.isWhitelisted("GET", "/api/v1/system/info")).isFalse();
    }
}
