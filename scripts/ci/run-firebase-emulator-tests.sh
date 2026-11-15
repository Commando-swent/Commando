#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPOSITORY_ROOT}"

readonly TEST_ROOT="app/src/test"
readonly TEST_JAVA_ROOT="${TEST_ROOT}/java"
readonly OPEN_FIRESTORE_CONFIG="firebase.firestore-open-test.json"
readonly PRODUCTION_FIRESTORE_RULES="firestore.rules"
readonly SHARED_FIREBASE_CONFIG="firebase.json"
readonly FIREBASE_PROJECT_ID="demo-commando"
readonly FIREBASE_TOOLS_VERSION="${FIREBASE_TOOLS_VERSION:-15.32.1}"
# Every profile reruns testDebugUnitTest, which replaces this file, so each profile's coverage is
# preserved separately for jacocoTestReport.
readonly UNIT_TEST_COVERAGE_FILE="app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
readonly EMULATOR_COVERAGE_DIR="app/build/jacoco/firebaseEmulator"

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

  rm -f "${UNIT_TEST_COVERAGE_FILE}"
  ./gradlew :app:testDebugUnitTest "-PfirebaseEmulatorProfile=${profile}"

  if [[ ! -f "${UNIT_TEST_COVERAGE_FILE}" ]]; then
    echo "Firebase emulator profile ${profile} did not produce coverage data:" \
      "${UNIT_TEST_COVERAGE_FILE}" >&2
    exit 1
  fi
  mkdir -p "${EMULATOR_COVERAGE_DIR}"
  cp "${UNIT_TEST_COVERAGE_FILE}" "${EMULATOR_COVERAGE_DIR}/${profile}.exec"
}

# AI assistance: OpenAI Codex. Infer the namespace from each test's source directory.
discover_profiles() {
  local test_file relative_path expected_package declared_package profile
  while IFS= read -r -d '' test_file; do
    relative_path="${test_file#"${TEST_JAVA_ROOT}/"}"
    case "${relative_path}" in
      */emulator/auth/*) profile="auth" ;;
      */emulator/firestore/adapter/*) profile="firestoreAdapter" ;;
      */emulator/firestore/security/*) profile="firestoreSecurity" ;;
      */emulator/* | *EmulatorTest.kt) fail "${test_file} is outside an approved emulator profile" ;;
      *) continue ;;
    esac

    [[ "${test_file}" == "${TEST_JAVA_ROOT}/"* ]] ||
      fail "${test_file} is outside the Java test source root"
    [[ "${test_file}" == *EmulatorTest.kt ]] ||
      fail "${test_file} is in an emulator profile but is not named *EmulatorTest.kt"
    if [[ "${test_file}" == *SecurityRulesEmulatorTest.kt && "${profile}" != "firestoreSecurity" ]]; then
      fail "${test_file} must be in the firestore/security profile"
    fi

    expected_package="${relative_path%/*}"
    expected_package="${expected_package//\//.}"
    declared_package="$(awk '/^[[:space:]]*package[[:space:]]+/ { print $2; exit }' "${test_file}")"
    [[ "${declared_package}" == "${expected_package}" ]] ||
      fail "${test_file} must declare package ${expected_package}"
    printf '%s\n' "${profile}"
  done
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
  local config="$2"
  local emulators="$3"

  grep -Fxq "${profile}" <<< "${profiles}" || return 0
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

# Coverage from a previous run must never be reported as part of this one.
rm -rf "${EMULATOR_COVERAGE_DIR}"

profiles="$(find "${TEST_ROOT}" -type f -name '*Test.kt' -print0 | discover_profiles)"
if [[ -z "${profiles}" ]]; then
  echo "No Firebase emulator tests detected."
  exit 0
fi

run_profile "auth" "${SHARED_FIREBASE_CONFIG}" "auth"
run_profile "firestoreAdapter" "${OPEN_FIRESTORE_CONFIG}" "firestore"

if grep -Fxq "firestoreSecurity" <<< "${profiles}"; then
  validate_security_rules_config
fi
run_profile "firestoreSecurity" "${SHARED_FIREBASE_CONFIG}" "firestore,auth"
