FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="PlainJournal Trade Service" \
      org.opencontainers.image.description="Trade service runtime for local multi-instance experiments"

RUN addgroup -S -g 10001 plainjournal \
    && adduser -S -D -H -h /home/plainjournal -u 10001 -G plainjournal plainjournal \
    && mkdir -p /opt/plainjournal /home/plainjournal /tmp/plainjournal \
    && chown -R plainjournal:plainjournal /opt/plainjournal /home/plainjournal /tmp/plainjournal

WORKDIR /opt/plainjournal

COPY --chown=plainjournal:plainjournal \
    services/trade-service/target/trade-service-1.0.2-SNAPSHOT.jar \
    app.jar
COPY --chown=plainjournal:plainjournal \
    services/trade-service/docker/entrypoint.sh \
    entrypoint.sh

RUN chmod 0555 /opt/plainjournal/entrypoint.sh \
    && chmod 0444 /opt/plainjournal/app.jar

ENV HOME=/home/plainjournal \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -Djava.io.tmpdir=/tmp/plainjournal"

USER 10001:10001

EXPOSE 18104

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:18104/actuator/health/liveness || exit 1

ENTRYPOINT ["/opt/plainjournal/entrypoint.sh"]
