#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ./deploy.sh [--dry-run] [patch|minor|major|VERSION]

Default:
  ./deploy.sh patch

Examples:
  ./deploy.sh
  ./deploy.sh minor
  ./deploy.sh 262.1.0
  ./deploy.sh --dry-run patch

The script:
  1. Requires a clean git worktree.
  2. Bumps the plugin version in build.gradle.kts.
  3. Updates README.md and docs/updatePlugins.xml.
  4. Runs ./gradlew clean buildPlugin.
  5. Commits the release metadata.
  6. Creates and pushes tag v<VERSION>.
  7. Creates a GitHub release with build/distributions/Helidon-<VERSION>.zip.

Set GITHUB_REPOSITORY=owner/repo to override the repository parsed from origin.
USAGE
}

die() {
  echo "deploy.sh: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

export GIT_EDITOR=true
export GIT_SEQUENCE_EDITOR=true
export GIT_MERGE_AUTOEDIT=no
export GIT_TERMINAL_PROMPT=0
export GIT_SSH_COMMAND="${GIT_SSH_COMMAND:-ssh -o BatchMode=yes}"

dry_run=false
bump="patch"
bump_set=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --dry-run)
      dry_run=true
      ;;
    *)
      if [[ "$bump_set" == true ]]; then
        die "only one version argument is supported"
      fi
      bump="$1"
      bump_set=true
      ;;
  esac
  shift
done

require_command git
require_command gh
require_command python3

[[ -x ./gradlew ]] || die "missing executable ./gradlew"
[[ -f build.gradle.kts ]] || die "missing build.gradle.kts"
[[ -f docs/updatePlugins.xml ]] || die "missing docs/updatePlugins.xml"

if [[ -n "$(git status --porcelain)" && "$dry_run" == false ]]; then
  die "git worktree is not clean; commit or stash changes before deploying"
fi

branch="$(git symbolic-ref --quiet --short HEAD)" || die "not on a branch"
[[ "$branch" == "main" ]] || die "deploy must run from main; current branch is $branch"

git fetch origin "$branch"

local_rev="$(git rev-parse "$branch")"
remote_rev="$(git rev-parse "origin/$branch")"
base_rev="$(git merge-base "$branch" "origin/$branch")"

if [[ "$local_rev" != "$remote_rev" ]]; then
  if [[ "$local_rev" == "$base_rev" ]]; then
    die "$branch is behind origin/$branch; pull first"
  elif [[ "$remote_rev" == "$base_rev" ]]; then
    die "$branch has unpushed commits; push or reset before deploying"
  else
    die "$branch and origin/$branch have diverged"
  fi
fi

gh auth status --hostname github.com >/dev/null 2>&1 ||
  die "gh is not authenticated for github.com"

github_repo="${GITHUB_REPOSITORY:-}"
if [[ -z "$github_repo" ]]; then
  origin_url="$(git remote get-url origin)"
  case "$origin_url" in
    git@github.com:*)
      github_repo="${origin_url#git@github.com:}"
      ;;
    https://github.com/*)
      github_repo="${origin_url#https://github.com/}"
      ;;
    ssh://git@github.com/*)
      github_repo="${origin_url#ssh://git@github.com/}"
      ;;
    *)
      die "cannot infer GitHub repository from origin URL: $origin_url"
      ;;
  esac
  github_repo="${github_repo%.git}"
fi

current_version="$(
  python3 - <<'PY'
import re
from pathlib import Path

match = re.search(r'(?m)^version = "([^"]+)"$', Path("build.gradle.kts").read_text())
if not match:
    raise SystemExit("could not find Gradle version assignment")
print(match.group(1))
PY
)"

python3 - "$current_version" "$github_repo" <<'PY'
import re
import sys
from pathlib import Path

current_version, github_repo = sys.argv[1:]

readme = Path("README.md").read_text()
readme_match = re.search(r'plugin version `([^`]+)`', readme)
if readme_match and readme_match.group(1) != current_version:
    raise SystemExit(
        f"README plugin version {readme_match.group(1)} does not match Gradle version {current_version}"
    )

update_xml = Path("docs/updatePlugins.xml").read_text()
version_match = re.search(r'\s+version="([^"]+)">', update_xml)
if not version_match:
    raise SystemExit("could not find plugin version in docs/updatePlugins.xml")
if version_match.group(1) != current_version:
    raise SystemExit(
        f"updatePlugins.xml version {version_match.group(1)} does not match Gradle version {current_version}"
    )

expected_url = (
    f"https://github.com/{github_repo}/releases/download/"
    f"v{current_version}/Helidon-{current_version}.zip"
)
url_match = re.search(r'\s+url="([^"]+)"', update_xml)
if not url_match:
    raise SystemExit("could not find release URL in docs/updatePlugins.xml")
if url_match.group(1) != expected_url:
    raise SystemExit(
        f"updatePlugins.xml release URL does not match current version; expected {expected_url}"
    )

gradle = Path("build.gradle.kts").read_text()
since_match = re.search(r'(?m)^\s*sinceBuild = "([^"]+)"', gradle)
until_match = re.search(r'(?m)^\s*untilBuild = "([^"]+)"', gradle)
xml_idea = re.search(
    r'<idea-version since-build="([^"]+)"(?: until-build="([^"]+)")?\s*/>',
    update_xml,
)
if not since_match or not xml_idea:
    raise SystemExit("could not verify IDE compatibility metadata")
if since_match.group(1) != xml_idea.group(1):
    raise SystemExit("updatePlugins.xml since-build does not match build.gradle.kts sinceBuild")
gradle_until = until_match.group(1) if until_match else ""
xml_until = xml_idea.group(2) or ""
if gradle_until != xml_until:
    raise SystemExit("updatePlugins.xml until-build does not match build.gradle.kts untilBuild")
PY

case "$bump" in
  major|minor|patch)
    new_version="$(
      python3 - "$current_version" "$bump" <<'PY'
import re
import sys

version, bump = sys.argv[1], sys.argv[2]
match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
if not match:
    raise SystemExit(f"cannot {bump}-bump non-numeric version: {version}")

major, minor, patch = map(int, match.groups())
if bump == "major":
    major += 1
    minor = 0
    patch = 0
elif bump == "minor":
    minor += 1
    patch = 0
else:
    patch += 1

print(f"{major}.{minor}.{patch}")
PY
    )"
    ;;
  *)
    [[ "$bump" =~ ^[0-9]+[.][0-9]+[.][0-9]+([-+][0-9A-Za-z.-]+)?$ ]] ||
      die "VERSION must look like 262.1.0, or use patch/minor/major"
    new_version="$bump"
    ;;
esac

[[ "$new_version" != "$current_version" ]] || die "new version is the same as current version: $current_version"

tag="v$new_version"
asset="build/distributions/Helidon-$new_version.zip"
release_url="https://github.com/$github_repo/releases/download/$tag/Helidon-$new_version.zip"

if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then
  die "local tag already exists: $tag"
fi

if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
  die "remote tag already exists: $tag"
fi

if gh release view "$tag" --repo "$github_repo" >/dev/null 2>&1; then
  die "GitHub release already exists: $tag"
fi

if [[ "$dry_run" == true ]]; then
  cat <<EOF
Dry run: no files, git refs, or GitHub releases will be changed.

Repository:       $github_repo
Branch:           $branch
Current version:  $current_version
New version:      $new_version
Tag:              $tag
Release URL:      $release_url
Artifact:         $asset

Commands that would run:
  ./gradlew clean buildPlugin
  git commit -m "Release $tag"
  git tag -a "$tag" -m "Release $tag"
  git push origin "$branch"
  git push origin "$tag"
  gh release create "$tag" "$asset" --repo "$github_repo" --title "Helidon $new_version" --notes "Helidon IntelliJ Plugin $new_version" --verify-tag
EOF
  exit 0
fi

since_build="$(
  python3 - <<'PY'
import re
from pathlib import Path

match = re.search(r'(?m)^\s*sinceBuild = "([^"]+)"', Path("build.gradle.kts").read_text())
if not match:
    raise SystemExit("could not find sinceBuild")
print(match.group(1))
PY
)"

until_build="$(
  python3 - <<'PY'
import re
from pathlib import Path

match = re.search(r'(?m)^\s*untilBuild = "([^"]+)"', Path("build.gradle.kts").read_text())
print(match.group(1) if match else "")
PY
)"

python3 - "$new_version" "$release_url" "$since_build" "$until_build" <<'PY'
import re
import sys
from pathlib import Path

version, release_url, since_build, until_build = sys.argv[1:]

def rewrite(path, pattern, replacement, description):
    file = Path(path)
    text = file.read_text()
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"could not update {description} in {path}")
    file.write_text(updated)

rewrite("build.gradle.kts",
        r'^version = "[^"]+"$',
        f'version = "{version}"',
        "Gradle plugin version")

readme = Path("README.md")
if readme.exists():
    text = readme.read_text()
    updated, count = re.subn(r'plugin version `[^`]+`',
                             f'plugin version `{version}`',
                             text,
                             count=1)
    if count:
        readme.write_text(updated)

rewrite("docs/updatePlugins.xml",
        r'^(\s+url=")[^"]+(")$',
        rf'\g<1>{release_url}\g<2>',
        "plugin release URL")

rewrite("docs/updatePlugins.xml",
        r'^(\s+version=")[^"]+(">)$',
        rf'\g<1>{version}\g<2>',
        "plugin update version")

if until_build:
    rewrite("docs/updatePlugins.xml",
            r'^(\s+<idea-version since-build=")[^"]+(" until-build=")[^"]+("/>)$',
            rf'\g<1>{since_build}\g<2>{until_build}\g<3>',
            "IDE compatibility bounds")
else:
    rewrite("docs/updatePlugins.xml",
            r'^(\s+<idea-version since-build=")[^"]+("[^/]*/>)$',
            rf'\g<1>{since_build}\g<2>',
            "IDE since-build")
PY

echo "Building Helidon plugin $new_version..."
./gradlew clean buildPlugin

[[ -f "$asset" ]] || die "expected plugin artifact not found: $asset"

git diff --check

git add build.gradle.kts README.md docs/updatePlugins.xml
git -c commit.gpgsign=false commit -m "Release $tag"
git -c tag.gpgSign=false tag -a "$tag" -m "Release $tag"

git push origin "$branch"
git push origin "$tag"

gh release create "$tag" "$asset" \
  --repo "$github_repo" \
  --title "Helidon $new_version" \
  --notes "Helidon IntelliJ Plugin $new_version" \
  --verify-tag

echo "Released $tag"
echo "Artifact: $asset"
echo "Update metadata: docs/updatePlugins.xml"
