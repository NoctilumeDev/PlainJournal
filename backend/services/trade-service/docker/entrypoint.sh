#!/bin/sh
set -eu

if [ -z "${SERVICE_IP:-}" ]; then
    SERVICE_IP="$(hostname -i | awk '{print $1}')"
    export SERVICE_IP
fi

if [ -z "${SERVICE_INSTANCE_ID:-}" ]; then
    SERVICE_INSTANCE_ID="${HOSTNAME:-trade-service}"
    export SERVICE_INSTANCE_ID
fi

if [ -z "${TRADE_OUTBOX_PUBLISHER_ID:-}" ]; then
    TRADE_OUTBOX_PUBLISHER_ID="$SERVICE_INSTANCE_ID"
    export TRADE_OUTBOX_PUBLISHER_ID
fi

exec java -jar /opt/plainjournal/app.jar "$@"
