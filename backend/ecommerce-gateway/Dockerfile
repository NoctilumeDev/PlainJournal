FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="PlainJournal Gateway" \
      org.opencontainers.image.description="Gateway runtime for local release-governance experiments"

RUN addgroup -S -g 10001 plainjournal \
    && adduser -S -D -H -h /home/plainjournal -u 10001 -G plainjournal plainjournal \
    && mkdir -p /opt/plainjournal /home/plainjournal /tmp/plainjournal \
    && chown -R plainjournal:plainjournal /opt/plainjournal /home/plainjournal /tmp/plainjournal

WORKDIR /opt/plainjournal

COPY --chown=plainjournal:plainjournal \
    ecommerce-gateway/target/ecommerce-gateway-0.1.0-SNAPSHOT.jar \
    app.jar
COPY --chown=plainjournal:plainjournal \
    ecommerce-gateway/docker/entrypoint.sh \
    entrypoint.sh

RUN chmod 0555 /opt/plainjournal/entrypoint.sh \
    && chmod 0444 /opt/plainjournal/app.jar

ENV HOME=/home/plainjournal \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -Djava.io.tmpdir=/tmp/plainjournal"

USER 10001:10001

EXPOSE 18000

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:18000/actuator/health/liveness || exit 1

ENTRYPOINT ["/opt/plainjournal/entrypoint.sh"]
