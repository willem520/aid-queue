#!/bin/zsh
# 启动号源监控。
# Proxyman 抓包所需的信任库（runtime/truststore.jks）已由 AppConfig 在代码中自动加载，无需额外 JVM 参数。
cd "$(dirname "$0")"
exec mvn spring-boot:run
