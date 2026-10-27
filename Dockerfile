# Build context is produced by scripts/package-linux.sh; no sources or credentials are copied.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl libsqlite3-0 sqlite3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir /data && chown 65532:65532 /data && chmod 700 /data
COPY --chmod=755 dotki /app/dotki
COPY web/ /app/web/
ARG DOTKI_VERSION=dev
ENV HOST=0.0.0.0 PORT=8080 WEB_ROOT=/app/web DOTKI_DB_PATH=/data/dotki.sqlite DOTKI_VERSION=$DOTKI_VERSION
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/app/dotki"]
