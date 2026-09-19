package com.terrascout.orchestrator.app.config;

import com.terrascout.orchestrator.app.interceptor.TokenInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册 Token 鉴权拦截器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenInterceptor tokenInterceptor;

    public WebConfig(TokenInterceptor tokenInterceptor) {
        this.tokenInterceptor = tokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 默认拦截全部路径；免鉴权白名单（GET /api/v1/health）由拦截器内部放行
        registry.addInterceptor(tokenInterceptor).addPathPatterns("/api/**");
    }
}
