FROM node:20 AS builder

ARG TARGETARCH

# arm 架构 optipng 编译预装依赖
RUN if [ "$TARGETARCH" = "arm64" ]; then \
    apt-get update && \
    apt-get install -y --no-install-recommends \
    optipng build-essential ca-certificates pkg-config libpng-dev zlib1g-dev python3 make gcc g++; \
    fi

WORKDIR /opt/ui

# 依赖缓存
COPY gms-ui/package.json ./package.json
COPY gms-ui/yarn.lock ./yarn.lock

RUN yarn global add yarn@v1.22.10 && \
    if [ "$TARGETARCH" = "amd64" ]; then \
    yarn install --frozen-lockfile; \
    elif [ "$TARGETARCH" = "arm64" ]; then \
    yarn install --frozen-lockfile --ignore-scripts && \
    PLATFORM="linux-arm" && \
    mkdir -p node_modules/optipng-bin/vendor/$PLATFORM && \
    ln -sf /usr/bin/optipng node_modules/optipng-bin/vendor/$PLATFORM/optipng; \
    else \
    yarn install --frozen-lockfile; \
    fi

COPY gms-ui/ ./

RUN yarn build --outDir ./dist

FROM nginx:alpine

COPY --from=builder /opt/ui/dist/ /usr/share/nginx/html/
COPY docker/build/nginx-ui.conf /etc/nginx/conf.d/default.conf

EXPOSE 8686
