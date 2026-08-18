#!/usr/bin/env bash
# ==============================================================================
# One-time upgrade of a pre-split (1.x) ccedb to the 2.0.0 schema.
#
# Use this when you want to apply the upgrade yourself — a DBA-run change window, or an environment
# where the services are not permitted to migrate. If you would rather let the services do it, skip
# this script and follow "Option B" in README.md instead.
#
#   ./run-upgrade.sh --host db-host --port 5433 --db ccedb --user cce_user [--dry-run]
#
# Reads the password from PGPASSWORD, or prompts. Runs everything in ONE transaction: either the whole
# upgrade lands or nothing does.
# ==============================================================================
set -euo pipefail

HOST=localhost; PORT=5432; DB=ccedb; USER=cce_user; DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --db)   DB="$2";   shift 2;;
    --user) USER="$2"; shift 2;;
    --dry-run) DRY_RUN=true; shift;;
    -h|--help) sed -n '2,14p' "$0"; exit 0;;
    *) echo "Unknown option: $1" >&2; exit 2;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PSQL=(psql -h "$HOST" -p "$PORT" -d "$DB" -U "$USER" -v ON_ERROR_STOP=1)

echo "Target: $USER@$HOST:$PORT/$DB"

# These files are copies. The authoritative versions ship inside each service as Flyway migrations;
# warn if they have drifted, because then this script would apply something the services will not.
check_drift() {
  local copy="$1" original="$2" label="$3"
  if [[ -f "$original" ]] && ! diff -q "$copy" "$original" >/dev/null; then
    echo "WARNING: $label differs from the service's Flyway copy:" >&2
    echo "         $original" >&2
    echo "         Re-copy it before running this upgrade." >&2
    return 1
  fi
}
drift=0
check_drift "$HERE/01-protocol-align-existing-schema.sql" \
            "$HERE/../../cce-protocol-service/src/main/resources/db/migration/V2__align_existing_schema.sql" \
            "01-protocol-align-existing-schema.sql" || drift=1
check_drift "$HERE/02-matcher-upgrade-from-monolith.sql" \
            "$HERE/../src/main/resources/db/migration/V2__upgrade_from_monolith_schema.sql" \
            "02-matcher-upgrade-from-monolith.sql" || drift=1
[[ $drift -eq 1 ]] && { echo "Aborting on script drift." >&2; exit 1; }

# The upgrade assumes the source is at monolith V9 or later: V9 reversed relatedAction direction
# inside protocol_definition.definition, and nothing here repeats that work.
echo "── Checking preconditions"
if ! "${PSQL[@]}" -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='step_instance'" | grep -q 1; then
  echo "ERROR: no step_instance table — this does not look like a CCE database." >&2; exit 1
fi
if "${PSQL[@]}" -tAc "SELECT 1 FROM information_schema.columns WHERE table_name='step_instance' AND column_name='step_status'" | grep -q 1; then
  echo "Nothing to do: step_instance already has step_status, so this database is already upgraded."
  exit 0
fi
# V9 is recorded in the monolith's own ledger. Distinguish "not applied" from "cannot tell", because
# a silent pass here would let an un-reversed protocol_definition through.
if "${PSQL[@]}" -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='flyway_schema_history'" | grep -q 1; then
  v9=$("${PSQL[@]}" -tAc "SELECT count(*) FROM flyway_schema_history WHERE version='9' AND success")
  if [[ "$v9" == "0" ]]; then
    echo "ERROR: monolith migration V9 is not recorded as applied." >&2
    echo "       V9 reverses relatedAction direction in protocol_definition; apply it first." >&2
    exit 1
  fi
  echo "   monolith V9 confirmed applied"
else
  echo "   WARNING: no flyway_schema_history table, so monolith V9 cannot be confirmed." >&2
  echo "            V9 reverses relatedAction direction inside protocol_definition.definition." >&2
  echo "            If it was never applied, protocol dependencies will be inverted after upgrade." >&2
  read -r -p "            Continue anyway? [y/N] " ok
  [[ "$ok" == "y" || "$ok" == "Y" ]] || exit 1
fi

undecidable=$("${PSQL[@]}" -tAc \
  "SELECT count(*) FROM step_instance WHERE state='COMPLETED' AND completed_at IS NULL AND completion_status IS NULL")
if [[ "$undecidable" != "0" ]]; then
  echo "ERROR: $undecidable completed step(s) have neither completed_at nor completion_status." >&2
  echo "       Their SLA outcome cannot be derived. Resolve them, then re-run." >&2
  exit 1
fi
echo "   preconditions OK"

if [[ "$DRY_RUN" == "true" ]]; then
  echo "── Dry run: rolling back at the end, nothing will persist"
  { echo "BEGIN;"; cat "$HERE/01-protocol-align-existing-schema.sql" "$HERE/02-matcher-upgrade-from-monolith.sql"; echo "ROLLBACK;"; } \
    | "${PSQL[@]}" -q
  echo "   dry run completed and rolled back"
  exit 0
fi

echo "── Applying the upgrade in a single transaction"
{ echo "BEGIN;"; cat "$HERE/01-protocol-align-existing-schema.sql" "$HERE/02-matcher-upgrade-from-monolith.sql"; echo "COMMIT;"; } \
  | "${PSQL[@]}"

echo "── Recording the migrations in each service's Flyway ledger"
# Without this the services would try to apply V2 again on startup. Baselining at 2 records both V1
# and V2 as accounted for, so each service starts straight into normal operation.
for tbl in flyway_schema_history_protocol flyway_schema_history_matcher; do
  "${PSQL[@]}" -q -c "
    CREATE TABLE IF NOT EXISTS $tbl (
      installed_rank INTEGER NOT NULL PRIMARY KEY, version VARCHAR(50), description VARCHAR(200) NOT NULL,
      type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, checksum INTEGER, installed_by VARCHAR(100) NOT NULL,
      installed_on TIMESTAMP NOT NULL DEFAULT now(), execution_time INTEGER NOT NULL, success BOOLEAN NOT NULL);
    INSERT INTO $tbl (installed_rank,version,description,type,script,installed_by,execution_time,success)
    SELECT 1,'2','<< Flyway Baseline >>','BASELINE','<< Flyway Baseline >>',current_user,0,true
    WHERE NOT EXISTS (SELECT 1 FROM $tbl);"
  echo "   $tbl baselined at version 2"
done

echo
echo "── Verifying"
"${PSQL[@]}" -f "$HERE/verify.sql"

cat <<'NOTE'

Done. Two things to expect:

  * The Compliance Service will raise OVERDUE deviations on its first sweep for steps that were
    sitting in the old DUE state. Their deadline had passed and DUE was not treated as a breach
    before; under 2.0.0 it is. This is correct, not a fault of the migration.

  * The Scheduler Service is retired by this release and will fail against this schema. Stop it
    before starting the 2.0.0 services. See README.md.
NOTE
