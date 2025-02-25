#!/usr/bin/env bash
set -e

mkdir -p results

SETUP_VALUE="$1"
echo "Running $SETUP_VALUE"

TEST_NAME=$(jq -r --arg setup "$SETUP_VALUE" '.[$setup].testName' setup.json)
LOG_FILE="results/${TEST_NAME}_$(date '+%Y-%m-%dT%H:%M:%S').log"

echo "Execution Setup" > "$LOG_FILE"
echo "$(jq -r --arg setup "$SETUP_VALUE" '.[$setup]' setup.json)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"
echo "----------------------------------------" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

echo "Running gRPC Auto Migration via Framework..." | tee -a "$LOG_FILE"
k6 run --env SETUP="$SETUP_VALUE" grpc-auto-test.js 2>&1 \
  | tee /dev/tty \
  | grep -v -E "running \(|default |time=|Init " \
  | sed '/^$/d' \
  >> "$LOG_FILE"
sleep 2m

echo "----------------------------------------" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

echo "Running gRPC Manual Migration..." | tee -a "$LOG_FILE"
k6 run --env SETUP="$SETUP_VALUE" grpc-manual-test.js 2>&1 \
  | tee /dev/tty \
  | grep -v -E "running \(|default |time=|Init " \
  | sed '/^$/d' \
  >> "$LOG_FILE"
sleep 2m

echo "----------------------------------------" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

echo "Running HTTP REST Existing API..." | tee -a "$LOG_FILE"
k6 run --env SETUP="$SETUP_VALUE" http-test.js 2>&1 \
  | tee /dev/tty \
  | grep -v -E "running \(|default |time=|Init " \
  | sed '/^$/d' \
  >> "$LOG_FILE"

echo "----------------------------------------" >> "$LOG_FILE"

echo "" | tee -a "$LOG_FILE"
echo "All tests completed. See '${LOG_FILE}' for details." | tee -a "$LOG_FILE"
