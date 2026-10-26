#!/bin/zsh
# 启动号源监控。
# Proxyman 抓包所需的信任库（runtime/truststore.jks）已由 AppConfig 在代码中自动加载，无需额外 JVM 参数。
cd "$(dirname "$0")"
# Homebrew mvn 包装脚本在 JAVA_HOME 为空时回落到最新 JDK；Lombok 在高版本 JDK 下
# 注解处理失效（log/getter 符号全部丢失），必须锁定 Java 21。
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
exec mvn spring-boot:run
