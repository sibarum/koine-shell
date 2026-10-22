#!/usr/bin/env bash
# Tries each portable construct.
#
# Options:
#   --json VALUE    A JSON file to read.
#   --times N       How many times. (default: 2)
#   --loud          Say more.
#   -h, --help      Show this help.

koine_re1='^[[:space:]]*(#|$)'
json= times=2 loud=0
while (( $# > 0 )); do
    case "$1" in
        --json) json=${2?--json needs a value}; shift 2 ;;
        --times) [[ "${2-}" =~ ^-?[0-9]+$ ]] || { echo '--times needs a number' >&2; exit 2; }; times=$2; shift 2 ;;
        --loud) loud=1; shift ;;
        -h|--help) sed -n 2,8p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (--help lists them)" >&2; exit 2 ;;
    esac
done

# Prints a line in a color, when the output is a terminal.
koine_print() {
    if [[ -t 1 ]]; then printf '\e[%sm%s\e[0m\n' "$1" "$2"; else printf '%s\n' "$2"; fi
}

declare -A m copy
m=()
m[B]=1
m[b]=2
koine_1=("${!m[@]}")
koine_2=${koine_1[0]}
for koine_3 in "${koine_1[@]:1}"; do koine_2+=",$koine_3"; done
printf '%s\n' "map: ${#m[@]} $koine_2"
copy=()
for koine_4 in "${!m[@]}"; do copy[$koine_4]=${m[$koine_4]}; done
copy[c]=3
if [[ -n "${copy[b]+set}" ]]; then koine_5='has b'; else koine_5='no b'; fi
printf '%s\n' "copy: ${#copy[@]} of ${#m[@]}, $koine_5"
read -r -d '' -a words <<< '  a  b c '
printf '%s\n' "words: ${#words[@]} ${words[1]}"
koine_6=abcdef
koine_7=ab
printf '%s\n' "take: ${koine_6:0:3} ${koine_7:0:9}"
if [[ '  # x' =~ $koine_re1 ]]; then koine_8=comment; else koine_8=code; fi
printf '%s\n' "match: $koine_8"
if [[ A == a ]]; then koine_9=same; else koine_9=different; fi
printf '%s\n' "case: $koine_9"

get_none() { local -n koine_result=$1; koine_result=(); return; }

get_one() { local -n koine_result=$1; koine_result=(x); return; }

get_two() { local -n koine_result=$1; koine_result=(x y); return; }

get_none koine_10
get_one koine_11
get_two koine_12
printf '%s\n' "none ${#koine_10[@]}, one ${#koine_11[@]}, two ${#koine_12[@]}"

get_greetings() {
    local -n koine_result=$1
    local who=$2
    local -a names=("${@:3}")
    local name
    local -a greetings
    greetings=()
    for name in "${names[@]}"; do greetings+=("$who $name"); done
    koine_result=("${greetings[@]}")
    return
}

get_greetings koine_13 hi 'a b' c
koine_14=${koine_13[0]}
for koine_15 in "${koine_13[@]:1}"; do koine_14+="; $koine_15"; done
printf '%s\n' "greet: $koine_14"
doc=$(jq -c . "$json")
ids=()
mapfile -t koine_16 < <(jq -c '.items[]?' <<< "$doc")
for item in "${koine_16[@]}"; do ids+=("$(jq -r '.id | values' <<< "$item")"); done
mapfile -t koine_17 < <(jq -c '.missing.items[]?' <<< "$doc")
for item in "${koine_17[@]}"; do ids+=(never); done
koine_18=${ids[0]}
for koine_19 in "${ids[@]:1}"; do koine_18+=" $koine_19"; done
printf '%s\n' "json: $(jq -r '.name | values' <<< "$doc") [$(jq -r '.nothing | values' <<< "$doc")] $koine_18 $(jq -r '(.count | tonumber? | floor) // 0' <<< "$doc")"
if [[ -z "$(jq -c '.linux | values' <<< "$doc")" ]]; then koine_20=True; else koine_20=False; fi
printf '%s\n' "missing field empty: $koine_20"
if jq -e '.on == true' <<< "$doc" >/dev/null; then koine_21=True; else koine_21=False; fi
printf '%s\n' "flag field: $koine_21"
if git --version >/dev/null 2>&1; then koine_22=True; else koine_22=False; fi
printf '%s\n' "probe git: $koine_22"
if git no-such-subcommand >/dev/null 2>&1; then koine_23=True; else koine_23=False; fi
printf '%s\n' "failing git: $koine_23"
if no-such-program-koine >/dev/null 2>&1; then koine_24=True; else koine_24=False; fi
printf '%s\n' "missing program: $koine_24"
lines=0
while IFS= read -r -u 3 line || [[ -n "$line" ]]; do lines=1; done 3< <(git --version)
wait $!
status=$?
printf '%s\n' "lines: $lines status $status"
version=$(git --version)
printf '%s\n' "capture: ${version:0:11}"
printf '%s\n' "path: $(basename -- a/b/c.txt) $(basename -- "$(dirname -- a/b/c.txt)")"
if [[ -z "$HOME" ]]; then koine_25='no HOME'; else koine_25='HOME set'; fi
printf '%s\n' "env: $koine_25"
n=0
while true; do n=3; break; done
(( ${#words[@]} > 2 && (loud || !(times < n)) )) && big=1 || big=0
if (( big )); then printf '%s\n' "big after $n"; else printf '%s\n' small; fi
if (( loud )); then koine_print 33 loud; fi
git --version >/dev/null 2>&1 || exit $?
git --version || exit $?
printf '%s\n' "interp: ${#ids[@]} items, ${#m[@]} keys"
exit 7
