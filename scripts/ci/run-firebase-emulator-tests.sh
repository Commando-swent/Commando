#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPOSITORY_ROOT}"

readonly TEST_ROOT="app/src/test"
readonly EMULATOR_ROOT="${TEST_ROOT}/java/com/android/sample/emulator"
readonly AUTH_ROOT="${EMULATOR_ROOT}/auth"
readonly FIRESTORE_ADAPTER_ROOT="${EMULATOR_ROOT}/firestore/adapter"
readonly FIRESTORE_SECURITY_ROOT="${EMULATOR_ROOT}/firestore/security"
readonly OPEN_FIRESTORE_CONFIG="firebase.firestore-open-test.json"
readonly PRODUCTION_FIRESTORE_RULES="firestore.rules"
readonly SHARED_FIREBASE_CONFIG="firebase.json"
readonly FIREBASE_PROJECT_ID="demo-commando"
readonly FIREBASE_TOOLS_VERSION="${FIREBASE_TOOLS_VERSION:-15.32.1}"

fail() {
  echo "Firebase emulator test configuration error: $*" >&2
  exit 1
}

run_gradle_profile() {
  local profile="$1"
  local -a required_hosts
  case "${profile}" in
    auth) required_hosts=("FIREBASE_AUTH_EMULATOR_HOST") ;;
    firestoreAdapter) required_hosts=("FIRESTORE_EMULATOR_HOST") ;;
    firestoreSecurity)
      required_hosts=("FIRESTORE_EMULATOR_HOST" "FIREBASE_AUTH_EMULATOR_HOST")
      ;;
    *) fail "unknown internal Firebase emulator profile: ${profile}" ;;
  esac

  local host_variable
  for host_variable in "${required_hosts[@]}"; do
    [[ -n "${!host_variable:-}" ]] || fail "${host_variable} is not set"
  done

  ./gradlew :app:testDebugUnitTest "-PfirebaseEmulatorProfile=${profile}"
}

validate_package() {
  local test_file="$1"
  local package_pattern="$2"
  grep -Eq "^[[:space:]]*package[[:space:]]+${package_pattern}([.][A-Za-z_][A-Za-z0-9_]*)*[[:space:]]*$" \
    "${test_file}" || fail "${test_file} must declare a package matching its emulator profile"
}

profile_has_tests() {
  local profile_root="$1"
  [[ -d "${profile_root}" ]] &&
    [[ -n "$(find "${profile_root}" -type f -name '*EmulatorTest.kt' -print -quit)" ]]
}

validate_test_locations() {
  local test_file
  while IFS= read -r -d '' test_file; do
    case "${test_file}" in
      "${AUTH_ROOT}"/*)
        validate_package "${test_file}" 'com[.]android[.]sample[.]emulator[.]auth'
        ;;
      "${FIRESTORE_ADAPTER_ROOT}"/*)
        validate_package "${test_file}" 'com[.]android[.]sample[.]emulator[.]firestore[.]adapter'
        ;;
      "${FIRESTORE_SECURITY_ROOT}"/*)
        validate_package "${test_file}" 'com[.]android[.]sample[.]emulator[.]firestore[.]security'
        ;;
      *) fail "${test_file} is outside an approved emulator profile" ;;
    esac
  done < <(find "${TEST_ROOT}" -type f -name '*EmulatorTest.kt' -print0)

  while IFS= read -r -d '' test_file; do
    case "${test_file}" in
      "${FIRESTORE_SECURITY_ROOT}"/*) ;;
      *) fail "${test_file} must be in the firestore/security profile" ;;
    esac
  done < <(find "${TEST_ROOT}" -type f -name '*SecurityRulesEmulatorTest.kt' -print0)

  if [[ -d "${EMULATOR_ROOT}" ]]; then
    while IFS= read -r -d '' test_file; do
      fail "${test_file} is in an emulator profile but is not named *EmulatorTest.kt"
    done < <(
      find "${EMULATOR_ROOT}" -type f -name '*Test.kt' ! -name '*EmulatorTest.kt' -print0
    )
  fi
}

validate_security_rules_config() {
  local rules_path
  rules_path="$(${NODE_BINARY:-node} <<'NODE'
const fs = require("fs");
const path = require("path");

const config = JSON.parse(fs.readFileSync("firebase.json", "utf8"));
const rules = config.firestore?.rules;
if (typeof rules !== "string" || rules.trim() === "") {
  console.error("firebase.json must declare firestore.rules for the firestore/security profile");
  process.exit(1);
}
console.log(path.resolve(rules));
NODE
)" || fail "the production Firestore rules configuration is invalid"

  [[ -f "${rules_path}" ]] || fail "production Firestore rules do not exist: ${rules_path}"

  local production_rules_path
  production_rules_path="$(${NODE_BINARY:-node} -e \
    'console.log(require("path").resolve(process.argv[1]))' "${PRODUCTION_FIRESTORE_RULES}")"
  [[ "${rules_path}" == "${production_rules_path}" ]] ||
    fail "the firestore/security profile must use ${PRODUCTION_FIRESTORE_RULES}"
}

run_profile() {
  local profile="$1"
  local profile_root="$2"
  local config="$3"
  local emulators="$4"

  profile_has_tests "${profile_root}" || return 0
  [[ -f "${config}" ]] || fail "missing Firebase configuration for ${profile}: ${config}"

  echo "Running Firebase emulator profile: ${profile}"
  npx --yes "firebase-tools@${FIREBASE_TOOLS_VERSION}" emulators:exec \
    --non-interactive \
    --project "${FIREBASE_PROJECT_ID}" \
    --config "${config}" \
    --only "${emulators}" \
    "bash scripts/ci/run-firebase-emulator-tests.sh --run-gradle-profile ${profile}"
}

if (($# != 0)); then
  if (($# == 2)) && [[ "$1" == "--run-gradle-profile" ]]; then
    run_gradle_profile "$2"
    exit 0
  fi

  echo "This runner currently executes every detected Firebase emulator profile." >&2
  echo "Profile-scoped test filters may be added later without changing profile mappings." >&2
  exit 64
fi

validate_test_locations

if ! find "${TEST_ROOT}" -type f -name '*EmulatorTest.kt' -print -quit | grep -q .; then
  echo "No Firebase emulator tests detected."
  exit 0
fi

run_profile \
  "auth" \
  "${AUTH_ROOT}" \
  "${SHARED_FIREBASE_CONFIG}" \
  "auth"

run_profile \
  "firestoreAdapter" \
  "${FIRESTORE_ADAPTER_ROOT}" \
  "${OPEN_FIRESTORE_CONFIG}" \
  "firestore"

if profile_has_tests "${FIRESTORE_SECURITY_ROOT}"; then
  validate_security_rules_config
fi
run_profile \
  "firestoreSecurity" \
  "${FIRESTORE_SECURITY_ROOT}" \
  "${SHARED_FIREBASE_CONFIG}" \
  "firestore,auth"
