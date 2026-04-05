#!/bin/sh
# Gradle wrapper script — delegates to gradle/wrapper
set -e

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DIRNAME=$(dirname "$0")
CLASSPATH="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Use the Gradle wrapper jar if present, otherwise download
if [ ! -f "$CLASSPATH" ]; then
    echo "Downloading Gradle wrapper..."
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
    mkdir -p "$DIRNAME/gradle/wrapper"
    curl -sL "$WRAPPER_URL" -o "$CLASSPATH" 2>/dev/null || \
    wget -q "$WRAPPER_URL" -O "$CLASSPATH" 2>/dev/null || \
    { echo "ERROR: Could not download gradle-wrapper.jar"; exit 1; }
fi

exec "$JAVACMD" \
    $JAVA_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
