#!/bin/sh
set -eu

case "${1:-run}" in
    configure)
        : "${RUNNER_REPOSITORY_URL:?RUNNER_REPOSITORY_URL is required}"
        : "${RUNNER_REGISTRATION_TOKEN:?RUNNER_REGISTRATION_TOKEN is required}"
        ./config.sh --unattended --replace \
            --url "$RUNNER_REPOSITORY_URL" \
            --token "$RUNNER_REGISTRATION_TOKEN" \
            --name "${RUNNER_NAME:-classroom-nas}" \
            --labels "${RUNNER_LABELS:-classroom-nas}" \
            --work "${RUNNER_WORKDIR:-_work}"
        ;;
    run)
        test -f .runner
        exec ./run.sh
        ;;
    *)
        exec "$@"
        ;;
esac
