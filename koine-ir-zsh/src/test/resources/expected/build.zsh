#!/usr/bin/env zsh
# The Linux half of a release, ported to Zsh: what vexelray-dist's build.sh does, in Zsh's own terms. The reference
# script for the Zsh IR: arrays that do not split, modifiers, parameter flags, an always block, an anonymous function.
#
#   ./build.zsh                      everything, then start each application
#   ./build.zsh --only Pix           rebuild one application (its checkout's name) after a fix, then start it
#   ./build.zsh --skip-build         only start the applications already built
#   ./build.zsh --seconds 15         how long each application must stay up (8)
#   ./build.zsh --no-start           build only; for a VM with no desktop session
#
# Logs land in logs/linux/. Stops at the first library that fails; an application that fails is reported, and the
# run fails. Only a build where everything passed writes logs/linux/BUILD.txt, the commit each checkout was built
# from, which preflight.ps1 compares with the checkouts before it lets deploy.ps1 near the Linux binaries.
setopt no_unset pipe_fail

LIBS=(vexelray-build intrabuf atchung supirvast dxgi-bridge kronometer tactroller vexelray imagelib-wrapper vexelray-gui vexelray-framework ffmpeg-wrapper vexelray-codec vex-suite-common cott-engine cardinal concordance-indexer vexelray-sim-core vexelray-installer)
APPS=(mainframe text-editor-vexel-demo Pix vexplore vexelray-sim-fluid vexelray-sim-rigid calculator-vexel-demo vexelray-maistro vexelray-inspector)

dist=${0:A:h}
root=${dist:h}
logs=$dist/logs/linux
stamp=$logs/BUILD.txt

only= build=1 start=1 seconds=8
while (( $# )); do
    case $1 in
        --only) only=${2:?--only needs an application checkout}; shift 2 ;;
        --skip-build) build=0; shift ;;
        --no-start) start=0; shift ;;
        --seconds) seconds=${2:?--seconds needs a number}; shift 2 ;;
        -h|--help) sed -n '2,13p' $0 | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) print -u2 -r -- "unknown option: $1 (--help lists them)"; exit 2 ;;
    esac
done
[[ $(uname -s) == Linux ]] || { print -u2 -r -- "build.zsh is the Linux build; on Windows, run build.cmd"; exit 1; }
if [[ -n $only ]]; then
    (( ${APPS[(Ie)$only]} )) || { print -u2 -r -- "--only ${only}: not one of: $APPS"; exit 2; }
    [[ -f $stamp ]] || {
        print -u2 -r -- "--only rebuilds one application of a whole build, and there is none yet"
        exit 1
    }
fi

# An application is built here when its installer.json has a "linux" section.
has_linux() { grep -q '"linux"[[:space:]]*:' $root/$1/installer.json 2>/dev/null; }
# The files its linux section ships from target-linux/ (Windows sources are under target/), one a line.
linux_sources() {
    grep -o '"source"[[:space:]]*:[[:space:]]*"target-linux/[^"]*"' $root/$1/installer.json | sed 's/.*"\(target-linux\/[^"]*\)"/\1/'
}

mkdir -p $logs

# ---- build ------------------------------------------------------------------------------------------------------
if [[ $build == 1 ]]; then
    missing=()
    (( $+commands[mvn] )) || missing+=("Maven (mvn on PATH)")
    if (( $+commands[java] )); then
        major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
        (( ${major:-0} >= 25 )) || missing+=("GraalVM 25 (java on PATH is ${major:-unknown})")
    else
        missing+=("GraalVM 25 (java on PATH)")
    fi
    if (( ! $+commands[native-image] )) && [[ ! -x ${JAVA_HOME:-/nonexistent}/bin/native-image ]]; then
        missing+=("native-image: GraalVM 25 as JAVA_HOME, not a plain JDK")
    fi
    if (( $#missing )); then
        print -u2 -r -- "Install these first (DEPLOYING.md, One-time setup):"
        print -u2 -rl -- "  - "${^missing}
        exit 1
    fi

    if [[ -n $only ]]; then
        libs=() apps=($only)
    else
        # a build that fails part way must not pass for the last good one
        rm -f $stamp
        libs=($LIBS) apps=($APPS)
    fi

    for r in $libs; do
        print -r -- "[$r] clean install"
        if ! (cd $root/$r && mvn -B clean install) > $logs/$r.log 2>&1; then
            print -u2 -r -- "[$r] FAILED, see logs/linux/$r.log"
            exit 1
        fi
    done

    failed=0 built=()
    for r in $apps; do
        if ! has_linux $r; then print -r -- "[$r] no linux section in installer.json; not built"; continue; fi
        print -r -- "[$r] tests"
        if ! (cd $root/$r && mvn -B clean install) > $logs/app-$r.log 2>&1; then
            print -u2 -r -- "[$r] FAILED, see logs/linux/app-$r.log"
            failed=1
            continue
        fi
        print -r -- "[$r] native image"
        if ! (cd $root/$r && mvn -B -Pnative-linux clean package -DskipTests) > $logs/native-$r.log 2>&1; then
            print -u2 -r -- "[$r] NATIVE FAILED, see logs/linux/native-$r.log"
            failed=1
            continue
        fi
        for s in ${(f)"$(linux_sources $r)"}; do
            [[ -f $root/$r/$s ]] || {
                print -u2 -r -- "[$r] the build left no $s, which installer.json ships"
                failed=1
            }
        done
        built+=($r)
    done
    (( failed == 0 )) || { print -u2 -r -- "Some applications failed."; exit 1; }

    # What was built, a checkout a line: <checkout> <commit>, and "changed" when it had uncommitted changes.
    # preflight.ps1 reads it. --only keeps the other lines of the whole build it rebuilds one application of.
    built_from() {
        local changed=
        [[ -n $(git -C $root/$1 status --porcelain -uno) ]] && changed=' changed'
        print -r -- "$1 $(git -C $root/$1 rev-parse HEAD)$changed"
    }
    {
        {
            print -r -- "# Linux build, $(date '+%Y-%m-%d %H:%M'), on $(uname -n)"
            if [[ -n $only ]]; then grep -v -e '^#' -e "^$only " $stamp; fi
            for r in $libs $built; do built_from $r; done
        } > $stamp.tmp && mv $stamp.tmp $stamp
    } always { rm -f $stamp.tmp; }
    print -r -- "Build done: logs/linux/BUILD.txt"
fi

# ---- start ------------------------------------------------------------------------------------------------------
# The tests run on the JVM, so this is what catches a native image that cannot start: one missing a resource or a
# foreign call it never registered in its reachability metadata.
[[ $start == 1 ]] || exit 0
if [[ -z ${DISPLAY:-} && -z ${WAYLAND_DISPLAY:-} ]]; then
    print -u2 -r -- "No desktop session (neither DISPLAY nor WAYLAND_DISPLAY is set), so the applications cannot be started."
    print -u2 -r -- "Run this from a terminal on the VM's desktop, or pass --no-start to skip the check."
    exit 1
fi
targets=($APPS)
[[ -n $only ]] && targets=($only)
() {
    local dead=0 r s bin name pid
    for r in $targets; do
        has_linux $r || continue
        for s in ${(f)"$(linux_sources $r)"}; do
            case $s in *.tar.gz|*.tgz|*.txt|*.jar) continue ;; esac
            bin=$root/$r/$s name=${s:t}
            [[ -x $bin ]] || { print -u2 -r -- "  ${name}: no executable $bin"; dead=1; continue; }
            (cd ${bin:h} && exec $bin) > $logs/start-$name.log 2>&1 &
            pid=$!
            sleep $seconds
            if kill -0 $pid 2>/dev/null; then
                print -r -- "  $name up"
                kill $pid 2>/dev/null
                sleep 1
                kill -9 $pid 2>/dev/null
                wait $pid 2>/dev/null
            else
                print -u2 -r -- "  $name exited within ${seconds}s; see logs/linux/start-$name.log"
                dead=1
            fi
        done
    done
    (( dead == 0 )) || { print -u2 -r -- "An application does not start."; exit 1; }
}
print -r -- "Every Linux application started."
