#!/bin/bash
REPO="https://repo.maven.apache.org/maven2"
GROUP_PATH=$(echo $1 | tr . /)
ARTIFACT=$2
VERSION=$3

BASE_PATH="$GROUP_PATH/$ARTIFACT/$VERSION"
BASE_NAME="$ARTIFACT-$VERSION"

# Download POM
curl -L -o "${BASE_NAME}.pom" "$REPO/$BASE_PATH/${BASE_NAME}.pom"

# Download JAR
curl -L -o "${BASE_NAME}.jar" "$REPO/$BASE_PATH/${BASE_NAME}.jar" 2>/dev/null || true

# Install to local repo
LOCAL_REPO="$HOME/.m2/repository/$BASE_PATH"
mkdir -p "$LOCAL_REPO"
cp "${BASE_NAME}.pom" "$LOCAL_REPO/"
[ -f "${BASE_NAME}.jar" ] && cp "${BASE_NAME}.jar" "$LOCAL_REPO/"

echo "Installed $1:$2:$3"
