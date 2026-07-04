#!/usr/bin/env bash
# BER dual-format transition driver (temporary; removed at Phase 3).
# After adding the family's model ids to BONE_FORMAT_MODEL_IDS, run:
#   scripts/ber_family.sh <model_id> [<model_id> ...]
# Recompiles, regenerates block_models.json, restores the committed file, and
# splices in only the given (now bone-format) entries. Prints what changed.
set -e
cd "$(dirname "$0")/.."
./gradlew compileJava blockModels -q 2>&1 | grep -iE "wrote|error|exception" || true
cp src/main/resources/lib/minecraft/renderer/block_models.json ./_fresh.json
git checkout -- src/main/resources/lib/minecraft/renderer/block_models.json
python3 scripts/ber_splice.py ./_fresh.json "$@"
rm -f ./_fresh.json
python3 - "$@" <<'EOF'
import json, subprocess, sys
ids = sys.argv[1:]
head = json.loads(subprocess.check_output(['git', 'show', 'HEAD:src/main/resources/lib/minecraft/renderer/block_models.json']))
after = json.load(open('src/main/resources/lib/minecraft/renderer/block_models.json'))
mb, ma = head['models'], after['models']
changed = [k for k in ma if json.dumps(ma[k], sort_keys=True) != json.dumps(mb.get(k), sort_keys=True)]
print('changed vs HEAD:', changed)
for mid in ids:
    c = ma[mid]
    print(' ', mid, 'fmt', 'bones' if 'bones' in c['model'] else 'elem',
          '| y_axis', c.get('y_axis'), '| invYRot', c.get('inventory_y_rotation'),
          '| invTx', bool(c.get('inventory_transform')), '| entity_flip', c.get('entity_flip'),
          '| tinted', c.get('tinted'))
EOF
