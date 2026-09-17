# LingXi agent development sandbox image

The general-purpose container image a LingXi Docker sandbox runs in. It contains
JDK 21 (Temurin), Maven, Git, Node.js, npm and bash, so an agent can build and
inspect a project inside the sandbox without ever mutating the sandbox OS.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File .\harness-sandbox-docker\src\main\docker\build.ps1
```

```sh
sh harness-sandbox-docker/src/main/docker/build.sh
```

Both scripts build the image as `lingxi-agent-dev:latest` and run a smoke check
(`java -version`, `mvn -version`, `git --version`, `node --version`) inside the
image.

## Usage

`harness-sandbox-docker` uses this image by default — see
`com.summit.runtime.sandbox.DockerSandboxImage#DEFAULT`. Override it per
deployment with the workspace image configuration
(`lingxi.agent.container-image` in the example application).

Containers are reused by name. When the configured image no longer matches the
image a framework-managed container was created from, the framework removes and
recreates that container automatically, so switching images never leaves an
agent in a container that lacks the toolchain. Containers the framework does not
manage are never removed.
