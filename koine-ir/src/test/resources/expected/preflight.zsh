#!/usr/bin/env zsh
emulate -L zsh

SkipBuild=0 Seconds=8 BuildOnly=0
while (( $# )); do
    case $1 in
        --skip-build) SkipBuild=1; shift ;;
        --seconds) Seconds=${2:?--seconds needs a value}; shift 2 ;;
        --build-only) BuildOnly=1; shift ;;
        -h|--help)
            cat <<'EOF'
Usage: preflight.zsh [--skip-build] [--seconds SECONDS] [--build-only]

Everything before publishing: builds the stack, starts every application, checks the notices and versions, and generates the installers without publishing them.

1. build      build.cmd: libraries in order with their tests, then each application's tests and native image
2. start      each application's exe, from its installer.json, must still be running after a few seconds
3. linux      each application with a linux section needs its Linux binaries, built in the Linux VM from
              exactly the commits checked out here
4. notices    notices/notices.py --check
5. versions   lists each application whose installer.json version is already published
6. generate   deploy without -Publish, then the diff here and in ../sibarum.github.io

Nothing is uploaded, committed or pushed. Stops at the first failing step.

Options:
  --skip-build        Use the exes already in each application's target directory.
  --seconds SECONDS   How long each application must stay up. (8)
  --build-only        Stop after the notices: build, start, the Linux build and the notices checked, generating nothing.
  -h, --help          Show this help.
EOF
            exit 0
            ;;
        *) print -u2 -r -- "unknown option: $1 (--help lists them)"; exit 2 ;;
    esac
done
[[ $Seconds == <-> ]] || { print -u2 -r -- "--seconds takes a whole number, not $Seconds"; exit 2; }

dist=${0:A:h}
root=${dist:h}
logs=$dist/logs
mkdir -p "$logs"
suite=$(< $dist/installer-suite.json)
configs=()
for m in ${(f)"$(jq -c '.members[]?' <<< $suite)"}; do
    configs+=("${${:-$dist/$(jq -r '.config // empty' <<< $m)}:A}")
done

Step() { emulate -L zsh; local name=$1; print -r -- $'\e[36m\n'"== $name"$'\e[0m'; }

Fail() { emulate -L zsh; local msg=$1; print -r -- $'\e[31m'"PREFLIGHT FAILED: $msg"$'\e[0m'; exit 1; }

# A Python that answers --version: the Store's python.exe stub is on the path but runs nothing.
Find-Python() {
    emulate -L zsh
    local c
    for c in python py "$HOME/anaconda3/python.exe" "$HOME/miniconda3/python.exe"; do
        if whence -p -- "$c" >/dev/null && "$c" --version &>/dev/null; then REPLY=$c; return; fi
    done
    REPLY=
    return
}

if (( ! SkipBuild )); then
    Step build
    # Its output is checked for FAILED as well as its status, so a failure can never pass as a build.
    failed=0
    cmd /c "call \"$dist/build.cmd\"" | while IFS= read -r line || [[ -n $line ]]; do
        print -r -- "$line"
        if [[ $line =~ FAILED ]]; then failed=1; fi
    done
    status_=${pipestatus[1]}
    if (( status_ != 0 || failed )); then Fail 'the build; see logs/'; fi
fi
Step start
dead=()
for config in "${configs[@]}"; do
    app=$(< $config)
    for b in ${(f)"$(jq -c '.windows.binaries[]?' <<< $app)"}; do
        if [[ ! $(jq -r '.source // empty' <<< $b) == *.exe ]]; then continue; fi
        exe="${config:h}/$(jq -r '.source // empty' <<< $b)"
        if [[ ! -e $exe ]]; then dead+=("$(jq -r '.id // empty' <<< $app): no $exe"); continue; fi
        out="$logs/start-$(jq -r '.name // empty' <<< $b).log"
        (cd "${exe:h}" && exec "$exe") > "$out" 2> "$out.err" &
        p=$!
        sleep "$Seconds"
        if kill -0 "$p" 2>/dev/null; then
            print -r -- "  $(jq -r '.name // empty' <<< $b) up"
            kill "$p"
        else
            dead+=("$(jq -r '.name // empty' <<< $b) exited within ${Seconds}s; see logs/start-$(jq -r '.name // empty' <<< $b).log.err")
        fi
    done
done
if (( ${#dead} != 0 )); then
    for problem in "${dead[@]}"; do print -r -- $'\e[31m'"  $problem"$'\e[0m'; done
    Fail 'an application does not start'
fi

# The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of
# every checkout it built. They are fit to publish only while every one of those is still checked out.
Test-LinuxBuild() {
    emulate -L zsh
    local stamp line repo head config app b koineTemp1
    local -a f problems
    local -A builtFrom
    stamp=$logs/linux/BUILD.txt
    if [[ ! -e $stamp ]]; then reply=('no Linux build: logs/linux/BUILD.txt is missing'); return; fi
    builtFrom=()
    for line in ${(f)"$(< $stamp)"}; do
        if [[ $line =~ '^[[:space:]]*(#|$)' ]]; then continue; fi
        f=(${=line})
        builtFrom[${f[1]}]=${f[2]}
    done
    problems=()
    for repo in "${(@k)builtFrom}"; do
        head=$(git -C "$root/$repo" rev-parse HEAD)
        if [[ $head != "${builtFrom[$repo]}" ]]; then
            if [[ -z $head ]]; then koineTemp1=nothing; else koineTemp1=${head:0:9}; fi
            problems+=("${repo}: the Linux build is of ${builtFrom[$repo]:0:9}, the checkout is at $koineTemp1")
        fi
    done
    for config in "${configs[@]}"; do
        app=$(< $config)
        if jq -e '.linux == null' <<< $app >/dev/null; then continue; fi
        repo=${config:h:t}
        if (( ! ${+builtFrom[$repo]} )); then problems+=("${repo}: not in the Linux build"); fi
        for b in ${(f)"$(jq -c '.linux.binaries[]?' <<< $app)"}; do
            if [[ ! -e "${config:h}/$(jq -r '.source // empty' <<< $b)" ]]; then
                problems+=("$(jq -r '.id // empty' <<< $app): no $(jq -r '.source // empty' <<< $b)")
            fi
        done
    done
    reply=("${problems[@]}")
    return
}

Step linux
linuxApps=()
for config in "${configs[@]}"; do
    app=$(< $config)
    if jq -e '.linux != null' <<< $app >/dev/null; then linuxApps+=("$(jq -r '.name // empty' <<< $app)"); fi
done
if (( ${#linuxApps} == 0 )); then
    print -r -- '  no application has a linux section'
else
    [[ -t 0 ]] && interactive=1 || interactive=0
    Test-LinuxBuild
    problems=("${reply[@]}")
    while (( ${#problems} != 0 )); do
        for problem in "${problems[@]}"; do print -r -- $'\e[33m'"  $problem"$'\e[0m'; done
        print -r -- '  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh'
        if (( ! interactive )); then Fail 'the Linux build is missing, or not of these sources'; fi
        read -r '?  Press Enter once it has finished, to check again (Ctrl+C stops)'
        Test-LinuxBuild
        problems=("${reply[@]}")
    done
    print -r -- "  built in the VM from these sources: ${(j:, :)linuxApps}"
fi
Step notices
Find-Python
python=$REPLY
if [[ -z $python ]]; then Fail 'no Python found (python, py, or anaconda3)'; fi
if ! "$python" "$dist/notices/notices.py" --check; then Fail 'the notices'; fi
if (( BuildOnly )); then print -r -- $'\e[32m\nBuilt, started and checked.\e[0m'; exit 0; fi
Step versions
for config in "${configs[@]}"; do
    app=$(< $config)
    manifest="$dist/apps/$(jq -r '.id // empty' <<< $app)/manifest.json"
    latest=
    if [[ -e $manifest ]]; then latest=$(jq -r '.latest // empty' "$manifest"); fi
    if [[ $latest == "$(jq -r '.version // empty' <<< $app)" ]]; then
        print -r -- $'\e[33m'"  $(jq -r '.name // empty' <<< $app) $(jq -r '.version // empty' <<< $app) is already published; bump it in $config if it changed"$'\e[0m'
    else
        if [[ -z $latest ]]; then koineTemp2=none; else koineTemp2=$latest; fi
        print -r -- "  $(jq -r '.name // empty' <<< $app) $(jq -r '.version // empty' <<< $app) (published: $koineTemp2)"
    fi
done
Step generate
if ! "${0:A:h}/deploy.zsh"; then Fail deploy; fi
Step diff
git -C "$dist" status --short
site=$root/sibarum.github.io
if [[ -e $site/.git ]]; then git -C "$site" status --short; fi
print -r -- $'\e[32m\nPreflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish.\e[0m'
