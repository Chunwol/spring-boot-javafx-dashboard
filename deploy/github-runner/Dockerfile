FROM ubuntu:24.04

ARG RUNNER_VERSION=2.337.0

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates curl docker.io docker-compose-v2 git jq libicu74 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /runner
RUN curl -fsSL \
        "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" \
        | tar -xz

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh

ENV RUNNER_ALLOW_RUNASROOT=1
ENTRYPOINT ["/entrypoint.sh"]
CMD ["run"]
