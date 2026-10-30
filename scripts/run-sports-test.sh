#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
fi
# Upstream marks this file as a required Gradle input even when empty.
[[ -f local.properties ]] || touch local.properties
exec ./gradlew :composeApp:run -Pnuvio.sportsTest=true --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx4g -XX:MaxMetaspaceSize=1g' \
  -Pkotlin.compiler.execution.strategy=in-process "$@"
