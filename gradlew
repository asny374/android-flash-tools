#!/bin/sh
# 精简版 Gradle wrapper 启动脚本
# 完整版见 https://github.com/gradle/gradle/blob/master/gradlew

PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  case "$link" in
    /*) PRG="$link" ;;
    *) PRG=$(dirname "$PRG")/"$link" ;;
  esac
done
APP_HOME=$(cd "$(dirname "$PRG")" && pwd)

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/jre/bin/java" ]; then
  JAVACMD="$JAVA_HOME/jre/bin/java"
else
  JAVACMD=java
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
