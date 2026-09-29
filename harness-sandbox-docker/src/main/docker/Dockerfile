# General-purpose development sandbox for LingXi agents.
#
# A sandbox is only useful when the runtimes a project needs are already inside
# it: JDK 21, Maven, Git, Node.js, npm and bash are baked into the image, so a
# task never has to mutate the sandbox operating system just to build or inspect
# a project. Build it once with build.ps1 (Windows) or build.sh (POSIX):
#
#   docker build -t lingxi-agent-dev:latest harness-sandbox-docker/src/main/docker
#
# harness-sandbox-docker uses this image by default (see DockerSandboxImage).
FROM maven:3.9.11-eclipse-temurin-21-alpine

RUN apk add --no-cache \
      bash \
      git \
      nodejs \
      npm \
    && java -version \
    && javac -version \
    && mvn -version \
    && git --version \
    && node --version \
    && npm --version

ENV JAVA_HOME=/opt/java/openjdk
ENV MAVEN_CONFIG=/root/.m2

# These cache directories live with the reused sandbox container. Declaring
# them also lets deployments attach named volumes without changing the image.
VOLUME ["/root/.m2", "/root/.npm"]

WORKDIR /workspace

CMD ["tail", "-f", "/dev/null"]
