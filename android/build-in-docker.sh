#!/usr/bin/env bash
set -e
cd /work
if [ ! -x /opt/gradle/bin/gradle ]; then
  echo "== downloading gradle"
  curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
  mkdir -p /opt/gradle && unzip -q /tmp/g.zip -d /opt && mv /opt/gradle-8.14.3/* /opt/gradle/
fi
export PATH=/opt/gradle/bin:$PATH
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# Generate a signing keystore once so the release APK is installable via sideload
if [ ! -f /work/release.jks ]; then
  echo "== generating keystore"
  keytool -genkeypair -v \
    -keystore /work/release.jks \
    -storepass tvhelp123 -keypass tvhelp123 \
    -alias tvhelp -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=device Help, OU=Home, O=FireHelp, L=Home, S=Home, C=GB"
fi
cat > /work/signing.properties <<EOF
storeFile=release.jks
storePassword=tvhelp123
keyAlias=tvhelp
keyPassword=tvhelp123
EOF

echo "== building"
set +e
gradle :app:assembleRelease --no-daemon --stacktrace --console=plain > /work/build-out.log 2>&1
EC=$?
set -e
echo "EXIT=$EC"
echo "---- error summary ----"
grep -nE "FAILURE:|What went wrong|> |error:|e: |Caused by:|BUILD" /work/build-out.log | grep -vE "^\s*at " | head -40
echo "---- apk ----"
ls -la app/build/outputs/apk/release/ 2>&1 || true
