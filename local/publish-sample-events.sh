#!/usr/bin/env bash
# Publishes a handful of events so the local database has something in it.
#
# ORD-1001 is delivered incompletely on purpose: the ORDER_PAID event is skipped, so the first
# audit run reports a missing event and an incomplete history against a source that has all three.
#
# Uses kcat rather than Kafka's own console producer: the broker image here is the GraalVM-native
# one, which ships the binary and no shell tooling. kcat is a native client, so it also avoids the
# JVM-in-a-container problem this project ran into on Apple Silicon.
set -euo pipefail

NETWORK=${NETWORK:-orderservice_default}
BROKER=${BROKER:-kafka:9094}
KCAT_IMAGE=${KCAT_IMAGE:-edenhill/kcat:1.7.1}

publish() {
  local topic=$1 key=$2 payload=$3
  printf '%s:%s\n' "$key" "$payload" \
    | docker run --rm -i --network "$NETWORK" "$KCAT_IMAGE" \
        -b "$BROKER" -t "$topic" -K: -P
  echo "  -> $topic $key"
}

echo "publishing order events"
publish order-events ORD-1001 '{"eventId":"EVT-10001","orderId":"ORD-1001","eventType":"ORDER_CREATED","occurredAt":"2026-08-19T10:00:00Z","customerId":"CUST-1","status":"CREATED","currency":"PLN","totalAmount":150.00,"items":[{"productId":"P-1","quantity":2,"unitPrice":75.00,"currency":"PLN"}]}'
publish order-events ORD-1001 '{"eventId":"EVT-10003","orderId":"ORD-1001","eventType":"ORDER_COMPLETED","occurredAt":"2026-08-19T12:00:00Z","status":"COMPLETED","currency":"PLN","totalAmount":150.00}'
publish order-events ORD-1002 '{"eventId":"EVT-20001","orderId":"ORD-1002","eventType":"ORDER_CREATED","occurredAt":"2026-08-19T10:00:00Z","customerId":"CUST-2","status":"CREATED","currency":"PLN","totalAmount":320.00,"items":[{"productId":"P-7","quantity":4,"unitPrice":80.00,"currency":"PLN"}]}'
publish order-events ORD-1002 '{"eventId":"EVT-20002","orderId":"ORD-1002","eventType":"ORDER_COMPLETED","occurredAt":"2026-08-19T12:00:00Z","status":"COMPLETED","currency":"PLN","totalAmount":320.00}'

echo "publishing payment events"
publish payment-events ORD-1001 '{"eventId":"PAY-EVT-10001","paymentId":"PAY-1","orderId":"ORD-1001","eventType":"PAYMENT_COMPLETED","status":"PAID","amount":150.00,"currency":"PLN","occurredAt":"2026-08-19T11:00:00Z"}'

echo "done"
