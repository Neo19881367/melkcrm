# Runtime image for the backend. Build the distribution first, from the repository root:
#   ./gradlew :backend:installDist
#   docker build -t melkcrm-backend backend
# The image contains no secrets; configuration comes from environment variables at run time.
FROM eclipse-temurin:17-jre

RUN groupadd --system melk && useradd --system --gid melk --home-dir /app melk \
    && mkdir -p /app /data/images && chown -R melk:melk /data
COPY build/install/backend /app

USER melk
ENV PORT=8080 \
    IMAGE_DIR=/data/images
EXPOSE 8080
VOLUME ["/data/images"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q "200" <&3'
ENTRYPOINT ["/app/bin/backend"]
