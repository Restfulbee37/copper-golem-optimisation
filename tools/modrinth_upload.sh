#!/bin/sh
# Uploads a release jar to Modrinth as a new version of the project. The `modrinth` CI job runs this on
# version tags once the GitLab release exists.
#
# Usage: tools/modrinth_upload.sh <jar> <version, e.g. 1.0.0+26.3> <release notes file>
#
# Needs MODRINTH_PROJECT_ID and MODRINTH_TOKEN (a Modrinth personal access token with the "Create versions"
# scope). With MODRINTH_DRY_RUN=1 it prints the version it would create and uploads nothing; no token needed.
# MODRINTH_API_URL can point at https://staging-api.modrinth.com/v2 for a test upload.
#
# Game versions: every Minecraft release Modrinth knows that the jar accepts, i.e. the version itself and its
# patch releases (26.3 -> 26.3, 26.3.1, ...). Patch releases that come out later can be added on Modrinth.
set -eu

JAR="$1"
VERSION="$2"
NOTES_FILE="$3"
API="${MODRINTH_API_URL:-https://api.modrinth.com/v2}"
# Modrinth asks API clients to identify themselves.
USER_AGENT="RestfulBee/copper_golem_mod-release"
MOD_VERSION="${VERSION%%+*}"
MC_VERSION="${VERSION#*+}"

: "${MODRINTH_PROJECT_ID:?MODRINTH_PROJECT_ID is not set}"
if [ "${MODRINTH_DRY_RUN:-0}" != 1 ]; then
    : "${MODRINTH_TOKEN:?MODRINTH_TOKEN is not set}"
fi
test -f "$JAR" || { echo "No jar at $JAR"; exit 1; }
grep -q '[^[:space:]]' "$NOTES_FILE" || { echo "The release notes in $NOTES_FILE are empty"; exit 1; }

get() {
    curl --fail --silent --show-error --user-agent "$USER_AGENT" "$API$1"
}

# A re-run after a partial failure must not upload the same version twice.
if get "/project/$MODRINTH_PROJECT_ID/version" | jq -e --arg v "$VERSION" 'any(.[]; .version_number == $v)' >/dev/null; then
    echo "Modrinth already has version $VERSION; nothing to do."
    exit 0
fi

GAME_VERSIONS="$(get /tag/game_version | jq -c --arg mc "$MC_VERSION" \
    '[.[] | select(.version_type == "release" and (.version == $mc or (.version | startswith($mc + ".")))) | .version] | sort')"
if [ "$GAME_VERSIONS" = "[]" ]; then
    echo "Modrinth doesn't list Minecraft $MC_VERSION as a release yet."
    exit 1
fi
FABRIC_API_ID="$(get /project/fabric-api | jq -r .id)"

# 0.x versions are betas; 1.0.0 and later are releases.
case "$MOD_VERSION" in
    0.*) VERSION_TYPE=beta ;;
    *) VERSION_TYPE=release ;;
esac

DATA="$(jq -n \
    --arg project "$MODRINTH_PROJECT_ID" \
    --arg name "$MOD_VERSION for Minecraft $MC_VERSION" \
    --arg version "$VERSION" \
    --arg type "$VERSION_TYPE" \
    --rawfile changelog "$NOTES_FILE" \
    --argjson games "$GAME_VERSIONS" \
    --arg fabric_api "$FABRIC_API_ID" \
    '{
        project_id: $project,
        name: $name,
        version_number: $version,
        version_type: $type,
        changelog: $changelog,
        game_versions: $games,
        loaders: ["fabric"],
        dependencies: [{ project_id: $fabric_api, dependency_type: "required" }],
        featured: false,
        status: "listed",
        file_parts: ["file"],
        primary_file: "file"
    }')"

if [ "${MODRINTH_DRY_RUN:-0}" = 1 ]; then
    echo "Dry run: would upload $(basename "$JAR") as:"
    printf '%s\n' "$DATA" | jq 'del(.changelog)'
    echo "Changelog: $(wc -l < "$NOTES_FILE") lines"
    exit 0
fi

STATUS="$(printf '%s' "$DATA" | curl --silent --show-error --user-agent "$USER_AGENT" \
    --header "Authorization: $MODRINTH_TOKEN" \
    --form "data=@-;type=application/json" \
    --form "file=@$JAR;type=application/java-archive" \
    --output modrinth_response.json --write-out '%{http_code}' \
    "$API/version")"
if [ "$STATUS" != 200 ]; then
    echo "Modrinth refused the upload with HTTP $STATUS:"
    cat modrinth_response.json
    exit 1
fi
jq '{id, name, version_number, game_versions}' modrinth_response.json
