#!/usr/bin/env bash
# Step up concurrency to find the breaking point.
#   ./loadtest/run-steps.sh https://your-app.onrender.com
BASE_URL=${1:-http://localhost:8080}
DURATION=${DURATION:-20s}
STEPS=${STEPS:-"10 25 50 100 200 400 800"}
mkdir -p loadtest/results
for v in $STEPS; do
  echo -e "\n===== $v concurrent users ====="
  k6 run -q -e BASE_URL="$BASE_URL" -e VUS="$v" -e DURATION="$DURATION" loadtest/booking.js | grep RESULT
done
echo -e "\nSummary:"
for v in $STEPS; do cat "loadtest/results/vus-$v.txt"; done
