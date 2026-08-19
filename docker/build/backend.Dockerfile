ARG RUNTIME_JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /opt/build

# 1. 优先复制 pom 缓存依赖
COPY pom.xml ./pom.xml
COPY gms-server/pom.xml ./gms-server/pom.xml

RUN mvn dependency:resolve -B --no-transfer-progress

# 2. 复制源码并执行打包
COPY gms-server/src ./gms-server/src
RUN mvn package -B -DskipTests --no-transfer-progress

# 3. 准备运行时产物目录
RUN mkdir -p /opt/server_backup && \
    cp ./gms-server/target/BeiDou.jar /opt/server_backup/BeiDou.jar && \
    cp ./gms-server/src/main/resources/application.yml /opt/server_backup/application.yml

COPY gms-server/wz /opt/server_backup/wz
COPY gms-server/wz-zh-CN /opt/server_backup/wz-zh-CN
COPY gms-server/scripts /opt/server_backup/scripts
COPY gms-server/scripts-zh-CN /opt/server_backup/scripts-zh-CN

# 4. 运行镜像
FROM $RUNTIME_JRE_IMAGE

COPY --from=builder /opt/server_backup /opt/server_backup
COPY docker/build/entrypoint-nightly.sh /entrypoint-nightly.sh
RUN chmod +x /entrypoint-nightly.sh

VOLUME /opt/server

EXPOSE 8686 8484 7575 7576 7577

ENTRYPOINT ["/entrypoint-nightly.sh"]
