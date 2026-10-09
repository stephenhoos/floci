#!/bin/sh
set -eu
cd -- "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
if [ -n "${JAVA_HOME:-}" ]; then
    java_command="$JAVA_HOME/bin/java"
else
    java_command=java
fi
java_version="$("$java_command" -version 2>&1 | head -n 1)"
case "$java_version" in
    *'"25.'* | *'"25"'*) ;;
    *) echo "Floci requires Java 25. Set JAVA_HOME to a Java 25 installation. Found: $java_version" >&2; exit 1 ;;
esac
export FLOCI_VERSION="$(cat version.txt)"
export FLOCI_TLS_ENABLED="${FLOCI_TLS_ENABLED:-false}"
export FLOCI_SERVICES_UI_ENABLED="${FLOCI_SERVICES_UI_ENABLED:-false}"
export FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED="${FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED:-false}"
exec "$java_command" --enable-native-access=ALL-UNNAMED -jar quarkus-app/quarkus-run.jar "$@"
