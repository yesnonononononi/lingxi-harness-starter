$ErrorActionPreference = 'Stop'

# Builds the general-purpose agent sandbox image used by harness-sandbox-docker.
# The build context is this script's directory, so it can be run from anywhere.
$imageName = 'lingxi-agent-dev:latest'
$contextDir = $PSScriptRoot

docker build --pull --tag $imageName $contextDir
docker run --rm $imageName sh -lc 'java -version && javac -version && mvn -version && git --version && node --version && npm --version'

Write-Host "Sandbox image is ready: $imageName"
