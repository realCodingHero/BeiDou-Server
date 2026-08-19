# 使用基础镜像
FROM ubuntu:20.04 AS builder

# 安装需要的工具（curl, jq）
RUN apt-get update && \
    apt-get install -y curl jq && \
    apt-get clean

ARG GITHUB_OWNER=BeiDouMS
ARG GITHUB_REPO=BeiDou-Server

ARG RELEASE_PREFIX_GIT_TAG=Release
ARG RELEASE_VERSION
ARG TARGETARCH

RUN arch=$(case "$TARGETARCH" in amd64) echo x64 ;; arm64) echo arm64 ;; esac) \
 && curl -s https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/tags/${RELEASE_VERSION} \
    | jq -r --arg suffix "${arch}.tar.gz" \
      '.assets[] | select(.name | endswith($suffix)) | .browser_download_url' \
    | xargs -n 1 curl -fsSL -o latest_release.tar.gz

RUN mkdir -p /opt/server_unzip

RUN tar -xzvf latest_release.tar.gz -C /opt/server_unzip --strip-components=1

FROM ubuntu:20.04

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY --from=builder /opt/server_unzip /opt/server_backup
COPY docker/build/entrypoint-release.sh /entrypoint-release.sh
RUN chmod +x /entrypoint-release.sh

VOLUME /opt/server

EXPOSE 8686 8484 7575 7576 7577

ENTRYPOINT ["/entrypoint-release.sh"]
