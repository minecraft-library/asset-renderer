#!/usr/bin/env bash
# BER transition finalize: refresh block_models.sha256, run fast tests, print status.
# Does NOT commit. Run after a family's parity is confirmed <= baseline.
set -e
cd "$(dirname "$0")/.."
./gradlew test --tests "lib.minecraft.renderer.tooling.JsonResourceShaTest" > /dev/null 2>&1 || true
NEWSHA=$(grep -rhoE "block_models.json: fixture [0-9a-f]{64} but actual [0-9a-f]{64}" \
  build/test-results/test/TEST-*JsonResourceSha*.xml 2>/dev/null | grep -oE "[0-9a-f]{64}" | tail -1)
if [ -n "$NEWSHA" ]; then
  printf '%s' "$NEWSHA" > src/test/resources/lib/minecraft/renderer/block_models.sha256
  echo "sha refreshed: $NEWSHA"
else
  echo "sha unchanged (test passed with current fixture)"
fi
./gradlew test 2>&1 | grep -iE "BUILD (SUCCESSFUL|FAILED)|error:" | head -3
