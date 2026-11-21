#!/bin/sh
# Gradle start up script for POSIX generated for CloudHub Drive.
set -e

APP_HOME=$(cd "$(dirname "$0")" > /dev/null && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no java command could be found." >&2
    exit 1
fi

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \"-classpath\" \"$CLASSPATH\" org.gradle.wrapper.GradleWrapperMain \"$@\""
exec "$JAVACMD" "$@"
