#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
jdk=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
stage="$root/apps/desktop/src-tauri/target/package"
core="$stage/core"
jar="$root/services/core/app/target/interline-core.jar"
"$jdk/bin/java" -version 2>&1 | head -1 | grep -E '"21\.' >/dev/null
test -f "$jar"
mkdir -p "$stage"
work=$(mktemp -d "$stage/analysis.XXXXXX")
trap 'rm -rf "$work"' EXIT
"$jdk/bin/jdeps" --multi-release 21 --print-module-deps --ignore-missing-deps "$jar" > "$stage/jdeps-jar.txt"
(cd "$work" && "$jdk/bin/jar" xf "$jar")
"$jdk/bin/jdeps" --multi-release 21 --print-module-deps --ignore-missing-deps \
  --recursive --class-path "$work/BOOT-INF/lib/*" "$work/BOOT-INF/classes" > "$stage/jdeps-expanded.txt"
# jdeps includes optional integrations. Keep Spring beans, JDBC, HTTP/TLS and ZipFS at runtime.
modules=java.base,java.sql,java.naming,java.desktop,java.management,java.logging,java.xml,java.net.http,java.security.jgss,java.instrument,jdk.unsupported,jdk.crypto.ec,jdk.zipfs
rm -rf "$core"
mkdir -p "$core/native"
"$jdk/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$core/runtime"
echo "$modules" > "$stage/modules.txt"
cp "$jar" "$core/interline-core.jar"
sqlite=$(find "$work/BOOT-INF/lib" -name 'sqlite-jdbc-*.jar' -print)
unzip -p "$sqlite" org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib > "$core/native/libsqlitejdbc.dylib"
test -s "$core/native/libsqlitejdbc.dylib"
"$core/runtime/bin/java" -Xshare:dump
mkdir -p "$work/data" "$work/logs"
cd "$core"
# No agent, network calls or user database in training. The original executable jar stays byte-identical.
printf '%s\n' '{"token":"sk-test-cds-training-0000000000000000"}' | \
"runtime/bin/java" -XX:ArchiveClassesAtExit=interline-core.jsa -Dspring.context.exit=onRefresh \
  -Xss512k -XX:+UseSerialGC -Xmx256m -Djava.awt.headless=true -Dapple.awt.UIElement=true \
  "-Dorg.sqlite.lib.path=$core/native" -jar interline-core.jar \
  "--data-dir=$work/data" "--log-dir=$work/logs" --parent=stdio > "$stage/cds-training.log" 2>&1
test -s interline-core.jsa
# Tauri clears resource xattrs while assembling; jlink/CDS output includes read-only files.
chmod -R u+w "$core"
cmp "$jar" interline-core.jar
du -sk runtime interline-core.jar interline-core.jsa native > "$stage/core-sizes-kib.txt"
shasum -a 256 interline-core.jar > "$stage/core-sha256.txt"
echo "Core prepared: $core"
