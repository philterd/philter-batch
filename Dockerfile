# --- Build stage ---
FROM eclipse-temurin:25 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests

# --- Model stage ---
# The PhEye model ph-eye-pii-en-small at a pinned Hugging Face revision (the one Philter 4.0.0 and Philter AI Proxy
# ship). Each file is checked against its SHA-256, so a changed or corrupted download fails the build. The model is
# CC-BY-4.0, so its model card, which carries the attribution the license requires, is shipped beside it.
FROM ubuntu:24.04 AS model
ARG PHEYE_MODEL_REVISION=375cfd7bd6b4fa0ef618333048b9e94b92c35c94
RUN apt-get update && apt-get -y install --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /model
RUN for file in gliner_config.json tokenizer.json model.onnx; do \
        curl -fsSL -o "$file" "https://huggingface.co/philterd/ph-eye-pii-en-small/resolve/${PHEYE_MODEL_REVISION}/onnx/$file"; \
    done \
    && curl -fsSL -o README.md "https://huggingface.co/philterd/ph-eye-pii-en-small/resolve/${PHEYE_MODEL_REVISION}/README.md" \
    && printf '%s\n' \
        "5ec1db300425bad5846ae36ca732bb10a404a4d4eef37f620f1184fb2561918d  gliner_config.json" \
        "a3990e1379927bc3f9321b48a37f291bb29cd975fac294030ab989f9e32458ab  tokenizer.json" \
        "0ec8b8585ee8e60334efad4206e643b9575d6bf2f9b3e93604183f8aaaf3b40d  model.onnx" \
        "b2524b6216ab14aa6c8d2b8fcdd84706f89cf6b6c4a0ff20954d6d902c0b8594  README.md" \
        | sha256sum -c -

# --- Runtime image ---
FROM eclipse-temurin:25-jre
# The upgrade picks up package fixes published since the base image was last built.
# pebble is a service manager the base image ships outside any package; Philter Batch does not use it.
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/* && rm -f /usr/bin/pebble \
    && useradd --system --uid 10001 --no-create-home philter \
    && mkdir -p /opt/philter-batch/models/ph-eye-pii-en-small
COPY --from=model /model/ /opt/philter-batch/models/ph-eye-pii-en-small/
COPY --from=build /build/target/philter-batch.jar /opt/philter-batch/philter-batch.jar
# The philter-batch command, for the console pod (kubectl exec <console> -- philter-batch status <run_id>). It passes
# JAVA_TOOL_OPTIONS to java as arguments instead, so the JVM's notice that it picked them up stays out of the output.
RUN printf '%s\n' '#!/bin/sh' \
        'opts="${JAVA_TOOL_OPTIONS:-}"' \
        'unset JAVA_TOOL_OPTIONS' \
        '# shellcheck disable=SC2086' \
        'exec java $opts --enable-native-access=ALL-UNNAMED -jar /opt/philter-batch/philter-batch.jar "$@"' \
        > /usr/local/bin/philter-batch \
    && chmod 0755 /usr/local/bin/philter-batch
# No state on disk: input, output, and coordination are all in object storage, and documents are held in memory.
# The root file system can be mounted read-only, with a writable /tmp (in Kubernetes, an emptyDir with medium:
# Memory; with Docker, --tmpfs /tmp:exec): ONNX Runtime and the tokenizer extract their native libraries there at startup.
USER 10001
# The JVM sizes its heap from the container's memory limit. DJL (the tokenizer) caches its native library in /tmp.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    DJL_CACHE_DIR=/tmp/djl
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/philter-batch/philter-batch.jar"]
CMD ["--help"]
