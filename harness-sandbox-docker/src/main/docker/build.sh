#!/bin/sh
# Builds the general-purpose agent sandbox image used by harness-sandbox-docker.
# The build context is this script's directory, so it can be run from anywhere.
set -eu

image_name="lingxi-agent-dev:latest"
context_dir="$(cd "$(dirname "$0")" && pwd)"

docker build --pull --tag "$image_name" "$context_dir"
docker run --rm "$image_name" sh -lc 'java -version && javac -version && mvn -version && git --version && node --version && npm --version'

echo "Sandbox image is ready: $image_name"
