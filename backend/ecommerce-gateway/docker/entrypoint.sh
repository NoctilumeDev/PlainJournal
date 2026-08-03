#!/bin/sh
set -eu

if [ -z "${SERVICE_IP:-}" ]; then
    SERVICE_IP="$(hostname -i | awk '{print $1}')"
    export SERVICE_IP
fi

if [ -z "${SERVICE_INSTANCE_ID:-}" ]; then
    SERVICE_INSTANCE_ID="${HOSTNAME:-ecommerce-gateway}"
    export SERVICE_INSTANCE_ID
fi

exec java -jar /opt/plainjournal/app.jar "$@"
