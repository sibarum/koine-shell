#!/usr/bin/env bash
# Everything before publishing: builds the stack, starts every application, checks the notices and versions, and generates the installers without publishing them.
#
# 1. build      build.cmd: libraries in order with their tests, then each application's tests and native image
# 2. start      each application's exe, from its installer.json, must still be running after a few seconds
# 3. linux      each application with a linux section needs its Linux binaries, built in the Linux VM from
#               exactly the commits checked out here
# 4. notices    notices/notices.py --check
# 5. versions   lists each application whose installer.json version is already published
# 6. generate   deploy without -Publish, then the diff here and in ../sibarum.github.io
#
# Nothing is uploaded, committed or pushed. Stops at the first failing step.
#
# Options:
#   --skip-build    Use the exes already in each application's target directory.
#   --seconds N     How long each application must stay up. (default: 8)
#   --build-only    Stop after the notices: build, start, the Linux build and the notices checked, generating nothing.
#   -h, --help      Show this help.

koine_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
koine_re1='^[[:space:]]*(#|$)'
skip_build=0 seconds=8 build_only=0
while (( $# > 0 )); do
    case "$1" in
        --skip-build) skip_build=1; shift ;;
        --seconds)
            [[ "${2-}" =~ ^-?[0-9]+$ ]] || { echo '--seconds needs a number' >&2; exit 2; }
            seconds=$2
            shift 2
            ;;
        --build-only) build_only=1; shift ;;
        -h|--help) sed -n 2,18p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (--help lists them)" >&2; exit 2 ;;
    esac
done

# Prints a line in a color, when the output is a terminal.
koine_print() {
    if [[ -t 1 ]]; then printf '\e[%sm%s\e[0m\n' "$1" "$2"; else printf '%s\n' "$2"; fi
}

dist=$koine_dir
root=$(dirname -- "$dist")
logs="$dist/logs"
mkdir -p -- "$logs"
suite=$(jq -c . "$dist/installer-suite.json")
configs=()
mapfile -t koine_1 < <(jq -c '.members[]?' <<< "$suite")
for m in "${koine_1[@]}"; do configs+=("$(realpath -ms -- "$dist/$(jq -r '.config | values' <<< "$m")")"); done

step() { local name=$1; koine_print 36 $'\n'"== $name"; }

fail() { local msg=$1; koine_print 31 "PREFLIGHT FAILED: $msg"; exit 1; }

# A Python that answers --version: the Store's python.exe stub is on the path but runs nothing.
find_python() {
    local -n koine_result=$1
    local c
    for c in python py "$HOME/anaconda3/python.exe" "$HOME/miniconda3/python.exe"; do
        if command -v "$c" >/dev/null && "$c" --version >/dev/null 2>&1; then koine_result=$c; return; fi
    done
    koine_result=
    return
}

if (( !skip_build )); then
    step build
    # Its output is checked for FAILED as well as its status, so a failure can never pass as a build.
    failed=0
    while IFS= read -r -u 3 line || [[ -n "$line" ]]; do
        printf '%s\n' "$line"
        if [[ "$line" =~ FAILED ]]; then failed=1; fi
    done 3< <(cmd /c "call \"$dist/build.cmd\"")
    wait $!
    status=$?
    if (( status != 0 || failed )); then fail 'the build; see logs/'; fi
fi
step start
dead=()
for config in "${configs[@]}"; do
    app=$(jq -c . "$config")
    mapfile -t koine_2 < <(jq -c '.windows.binaries[]?' <<< "$app")
    for b in "${koine_2[@]}"; do
        if [[ "$(jq -r '.source | values' <<< "$b")" != *.exe ]]; then continue; fi
        exe="$(dirname -- "$config")/$(jq -r '.source | values' <<< "$b")"
        if [[ ! -e "$exe" ]]; then dead+=("$(jq -r '.id | values' <<< "$app"): no $exe"); continue; fi
        out="$logs/start-$(jq -r '.name | values' <<< "$b").log"
        (cd -- "$(dirname -- "$exe")" && exec "$exe") > "$out" 2> "$out.err" &
        p=$!
        sleep "$seconds"
        if kill -0 "$p" 2>/dev/null; then
            printf '%s\n' "  $(jq -r '.name | values' <<< "$b") up"
            kill "$p"
        else
            dead+=("$(jq -r '.name | values' <<< "$b") exited within ${seconds}s; see logs/start-$(jq -r '.name | values' <<< "$b").log.err")
        fi
    done
done
if (( ${#dead[@]} != 0 )); then
    for problem in "${dead[@]}"; do koine_print 31 "  $problem"; done
    fail 'an application does not start'
fi

# The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of
# every checkout it built. They are fit to publish only while every one of those is still checked out.
test_linux_build() {
    local -n koine_result=$1
    local stamp line repo head config app b koine_4
    local -a f problems koine_3 koine_5
    local -A built_from
    stamp="$logs/linux/BUILD.txt"
    if [[ ! -e "$stamp" ]]; then koine_result=('no Linux build: logs/linux/BUILD.txt is missing'); return; fi
    built_from=()
    mapfile -t koine_3 < "$stamp"
    for line in "${koine_3[@]}"; do
        if [[ "$line" =~ $koine_re1 ]]; then continue; fi
        read -r -d '' -a f <<< "$line"
        built_from["${f[0]}"]=${f[1]}
    done
    problems=()
    for repo in "${!built_from[@]}"; do
        head=$(git -C "$root/$repo" rev-parse HEAD)
        if [[ "$head" != "${built_from[$repo]}" ]]; then
            if [[ -z "$head" ]]; then koine_4=nothing; else koine_4=${head:0:9}; fi
            problems+=("$repo: the Linux build is of ${built_from[$repo]:0:9}, the checkout is at $koine_4")
        fi
    done
    for config in "${configs[@]}"; do
        app=$(jq -c . "$config")
        if [[ -z "$(jq -c '.linux | values' <<< "$app")" ]]; then continue; fi
        repo=$(basename -- "$(dirname -- "$config")")
        if [[ -z "${built_from[$repo]+set}" ]]; then problems+=("$repo: not in the Linux build"); fi
        mapfile -t koine_5 < <(jq -c '.linux.binaries[]?' <<< "$app")
        for b in "${koine_5[@]}"; do
            if [[ ! -e "$(dirname -- "$config")/$(jq -r '.source | values' <<< "$b")" ]]; then
                problems+=("$(jq -r '.id | values' <<< "$app"): no $(jq -r '.source | values' <<< "$b")")
            fi
        done
    done
    koine_result=("${problems[@]}")
    return
}

step linux
linux_apps=()
for config in "${configs[@]}"; do
    app=$(jq -c . "$config")
    if [[ -n "$(jq -c '.linux | values' <<< "$app")" ]]; then linux_apps+=("$(jq -r '.name | values' <<< "$app")"); fi
done
if (( ${#linux_apps[@]} == 0 )); then
    printf '%s\n' '  no application has a linux section'
else
    [[ -t 0 ]] && interactive=1 || interactive=0
    test_linux_build koine_6
    problems=("${koine_6[@]}")
    while (( ${#problems[@]} != 0 )); do
        for problem in "${problems[@]}"; do koine_print 33 "  $problem"; done
        printf '%s\n' '  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh'
        if (( !interactive )); then fail 'the Linux build is missing, or not of these sources'; fi
        read -r -p '  Press Enter once it has finished, to check again (Ctrl+C stops): '
        test_linux_build koine_7
        problems=("${koine_7[@]}")
    done
    koine_8=${linux_apps[0]}
    for koine_9 in "${linux_apps[@]:1}"; do koine_8+=", $koine_9"; done
    printf '%s\n' "  built in the VM from these sources: $koine_8"
fi
step notices
find_python python
if [[ -z "$python" ]]; then fail 'no Python found (python, py, or anaconda3)'; fi
if ! "$python" "$dist/notices/notices.py" --check; then fail 'the notices'; fi
if (( build_only )); then koine_print 32 $'\nBuilt, started and checked.'; exit 0; fi
step versions
for config in "${configs[@]}"; do
    app=$(jq -c . "$config")
    manifest="$dist/apps/$(jq -r '.id | values' <<< "$app")/manifest.json"
    latest=
    if [[ -e "$manifest" ]]; then latest=$(jq -r '.latest | values' "$manifest"); fi
    if [[ "$latest" == "$(jq -r '.version | values' <<< "$app")" ]]; then
        koine_print 33 "  $(jq -r '.name | values' <<< "$app") $(jq -r '.version | values' <<< "$app") is already published; bump it in $config if it changed"
    else
        if [[ -z "$latest" ]]; then koine_10=none; else koine_10=$latest; fi
        printf '%s\n' "  $(jq -r '.name | values' <<< "$app") $(jq -r '.version | values' <<< "$app") (published: $koine_10)"
    fi
done
step generate
if ! "$BASH" "$koine_dir/deploy.sh"; then fail deploy; fi
step diff
git -C "$dist" status --short
site="$root/sibarum.github.io"
if [[ -e "$site/.git" ]]; then git -C "$site" status --short; fi
koine_print 32 $'\nPreflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish.'
