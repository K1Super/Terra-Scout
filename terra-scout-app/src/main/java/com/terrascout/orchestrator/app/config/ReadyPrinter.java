package com.terrascout.orchestrator.app.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 内核就绪通知：Spring 上下文就绪后向 stdout 打印 {@code READY <actualPort>}。
 *
 * <p>Electron 主进程监听本行（正则 {@code ^READY\s+(\d+)$}）获取实际端口（server.port=0 时为随机端口）。
 * 必须使用 {@link System#out} 裸打印，不得经日志框架加前缀，否则破坏契约行。
 */
@Component
public class ReadyPrinter implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        WebServerApplicationContext ctx =
                (WebServerApplicationContext) event.getApplicationContext();
        int port = ctx.getWebServer().getPort();
        System.out.println("READY " + port);
    }
}
