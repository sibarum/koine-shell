#!/usr/bin/env zsh
emulate -L zsh

Json= Verbose=0 Count=3
while (( $# )); do
    case $1 in
        --json) Json=${2:?--json needs a value}; shift 2 ;;
        --verbose) Verbose=1; shift ;;
        --count) Count=${2:?--count needs a value}; shift 2 ;;
        -h|--help)
            cat <<'EOF'
Usage: probe.zsh [--json JSON] [--verbose] [--count COUNT]

Tries each portable construct.

Options:
  --json JSON     A JSON file to read.
  --verbose       Say more.
  --count COUNT   How many. (3)
  -h, --help      Show this help.
EOF
            exit 0
            ;;
        *) print -u2 -r -- "unknown option: $1 (--help lists them)"; exit 2 ;;
    esac
done
[[ $Count == <-> ]] || { print -u2 -r -- "--count takes a whole number, not $Count"; exit 2; }

typeset -A m
m=()
m[B]=1
m[b]=2
if (( ${+m[B]} )); then koineTemp1='has B'; else koineTemp1='no B'; fi
print -r -- "map: ${#m} ${(j:,:)${(@k)m}} ${m[b]} $koineTemp1"
koineTemp2='  a  b c '
words=(${=koineTemp2})
i=2
print -r -- "words: ${#words} ${words[2]} ${words[i+1]} ${(j/:/)words}"
print -r -- "take: ${${:-abcdef}:0:3} ${${:-ab}:0:9}"
if [[ '  # x' =~ '^[[:space:]]*(#|$)' ]]; then koineTemp3=comment; else koineTemp3=code; fi
print -r -- "match: $koineTemp3"
if [[ A == a ]]; then koineTemp4=same; else koineTemp4=different; fi
print -r -- "case: $koineTemp4"
if (( ${#words} < Count && ! (Verbose || ${#words} == 0) )); then koineTemp5=yes; else koineTemp5=no; fi
print -r -- "few: $koineTemp5"
(( ${#words} != 0 && ! Verbose )) && flag=1 || flag=0

# Lists, a bool, a map and nothing, returned from functions.
Get-None() { emulate -L zsh; reply=(); return; }

Get-Two() { emulate -L zsh; reply=(x y); return; }

Is-Short() { emulate -L zsh; local text=$1; [[ ${text:0:3} == "$text" ]]; return; }

Grow() { emulate -L zsh; local text=$1; REPLY=${text}x; return; }

Get-Pairs() { emulate -L zsh; local -A pairs; pairs=(); pairs[k]=v; reply=("${(@kv)pairs}"); return; }

Walk() {
    emulate -L zsh
    local text=$1
    local -a words
    words=("${@[2,-1]}")
    local word
    for word in "${words[@]}"; do
        if [[ $word == b ]]; then continue; fi
        if [[ $word == c ]]; then break; fi
        print -r -- "$text $word"
    done
    return
}

Here() { emulate -L zsh; REPLY="${${(%):-%x}:A:h:t}"; return; }

Get-None
koineTemp6=("${reply[@]}")
Get-Two
koineTemp7=("${reply[@]}")
Get-Two
koineTemp8=("${reply[@]}")
print -r -- "none ${#koineTemp6}, two ${#koineTemp7}, first ${koineTemp8[1]}"
if Is-Short ab; then
    print -r -- $'\e[32mab is short\e[0m'
elif (( flag )); then
    print -r -- flag
else
    print -r -- $'\e[31mneither\e[0m'
fi
grown=ab
while Grow "$grown"; koineTemp9=$REPLY; [[ $koineTemp9 != abxxx ]]; do Grow "$grown"; grown=$REPLY; done
Get-Pairs
typeset -A pairs
pairs=("${reply[@]}")
Here
koineTemp10=$REPLY
print -r -- "grown $grown, pairs ${pairs[k]}, here $koineTemp10"
Walk walk "${words[@]}"
Grow dropped
doc=$(< $Json)
ids=()
for item in ${(f)"$(jq -c '.items[]?' <<< $doc)"}; do ids+=("$(jq -r '.id // empty' <<< $item)"); done
for item in ${(f)"$(jq -c '.missing.items[]?' <<< $doc)"}; do ids+=(never); done
koineTemp11=(${(f)"$(jq -r '.tags[]?' <<< $doc)"})
print -r -- "json: $(jq -r '.name // empty' <<< $doc) [$(jq -r '.nothing // empty' <<< $doc)] ${(j: :)ids} $(jq -r '.size // 0' <<< $doc) ${(j:+:)koineTemp11}"
if jq -e '.linux == null' <<< $doc >/dev/null; then koineTemp12=True; else koineTemp12=False; fi
if jq -e '.on == true' <<< $doc >/dev/null; then koineTemp13=True; else koineTemp13=False; fi
print -r -- "missing field empty: $koineTemp12, on: $koineTemp13, sub-field: $(jq -r '.sub["my-name"] // empty' <<< $doc)"
if git --version &>/dev/null; then koineTemp14=True; else koineTemp14=False; fi
print -r -- "probe git: $koineTemp14"
if git no-such-subcommand &>/dev/null; then koineTemp15=True; else koineTemp15=False; fi
print -r -- "failing git: $koineTemp15"
if no-such-program-koine &>/dev/null; then koineTemp16=True; else koineTemp16=False; fi
print -r -- "missing program: $koineTemp16"
if "${0:A:h}/no-such-script.zsh" &>/dev/null; then koineTemp17=True; else koineTemp17=False; fi
print -r -- "missing script: $koineTemp17"
if whence -p -- git >/dev/null; then koineTemp18=True; else koineTemp18=False; fi
print -r -- "git on path: $koineTemp18"
git --version &>/dev/null || exit $?
git rev-parse --is-inside-work-tree
lines=0
git --version | while IFS= read -r line || [[ -n $line ]]; do lines=1; done
status_=${pipestatus[1]}
print -r -- "lines: $lines status $status_"
version=$(git --version)
print -r -- "capture: ${version:0:11}"
print -r -- "path: ${${:-a/b/c.txt}:t} ${${:-a/b/c.txt}:h:t} ${${:-${0:A:h}/x/../y}:A:t}"
if [[ $HOME == "$HOME" ]]; then koineTemp19=True; else koineTemp19=False; fi
print -r -- "home: $koineTemp19"
mkdir -p "${0:A:h}/probe-out"
(cd "${0:A:h}" && exec sleep 5) > "${0:A:h}/probe-out/sleep.log" 2> "${0:A:h}/probe-out/sleep.err" &
proc=$!
sleep 1
if kill -0 "$proc" 2>/dev/null; then kill "$proc"; print -r -- $'\e[33mspawned and killed\e[0m'; fi
if [[ -t 0 ]]; then read -r '?Press Enter to finish'; fi
print -r -- "interp: ${#ids} items, ${#m} keys"
exit 7
