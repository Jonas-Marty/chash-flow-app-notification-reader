# Android build environment for FinReader.
# Everything Android-specific lives in here so the host stays clean.
FROM eclipse-temurin:17-jdk-noble

ARG CMDLINE_TOOLS_VERSION=13114758
ARG ANDROID_PLATFORM=36
ARG BUILD_TOOLS=36.1.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/dev/.gradle \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ca-certificates openssh-client jq \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM}" \
        "build-tools;${BUILD_TOOLS}" \
    > /dev/null

# Build as a non-root user whose ids match the host account, so files written
# into the mounted workspace stay owned by the host user.
ARG USER_UID=1000
ARG USER_GID=1000
# Ubuntu ships a uid-1000 user already; reuse that slot rather than fighting it.
RUN if getent group "${USER_GID}" >/dev/null; then \
        groupmod -n dev "$(getent group "${USER_GID}" | cut -d: -f1)"; \
    else \
        groupadd -g "${USER_GID}" dev; \
    fi \
    && if getent passwd "${USER_UID}" >/dev/null; then \
        usermod -l dev -g "${USER_GID}" -d /home/dev -m -s /bin/bash "$(getent passwd "${USER_UID}" | cut -d: -f1)"; \
    else \
        useradd -m -u "${USER_UID}" -g "${USER_GID}" -s /bin/bash dev; \
    fi \
    && mkdir -p /home/dev/.gradle /home/dev/.android \
    && chown -R "${USER_UID}:${USER_GID}" /home/dev "${ANDROID_HOME}"

USER dev
WORKDIR /workspace
