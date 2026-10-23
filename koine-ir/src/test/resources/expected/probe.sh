#!/usr/bin/env bash
# Tries each portable construct.
#
# Options:
#   --json VALUE    A JSON file to read.
#   -h, --help      Show this help.

koine_re1='^[[:space:]]*(#|$)'
json=
while (( $# > 0 )); do
    case "$1" in
        --json) json=${2?--json needs a value}; shift 2 ;;
        -h|--help) sed -n 2,6p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (--help lists them)" >&2; exit 2 ;;
    esac
done

# Fails the script: the message in red, with the step it is in, then exit, which runs the cleanup.
koine_fail() {
    local message=$1
    if [[ -n ${koine_step-} ]]; then message="$koine_step failed: $message"; fi
    if [[ -t 2 ]]; then printf '\e[31m%s\e[0m\n' "$message" >&2; else printf '%s\n' "$message" >&2; fi
    exit 1
}

# The cleanup of each finally still open, run innermost first however the script ends.
koine_cleanups=()
koine_run_cleanups() {
    local i
    for (( i = ${#koine_cleanups[@]} - 1; i >= 0; i-- )); do "${koine_cleanups[i]}"; done
}
trap koine_run_cleanups EXIT
koine_cleanup_1() { printf '%s\n' 'cleaned up'; }
koine_cleanup_2() { printf '%s\n' 'cleaned up after the failure'; }

declare -A m
string_words() {
    local text=$1
    read -r -d '' -a reply <<< "$text" || true
}

string_take() {
    local text=$1 count=$2
    REPLY=${text:0:count}
}

list_join() {
    local -a items=("${@:2:$1}")
    shift $(($1 + 1))
    local separator=$1
    local item
    REPLY=${items[0]-}
    for item in "${items[@]:1}"; do REPLY+=$separator$item; done
}

path_join() {
    local -a parts=("${@:2:$1}")
    local IFS=/
    REPLY="${parts[*]}"
}

path_parent() {
    local path=$1
    REPLY=$(dirname -- "$path")
}

path_name() {
    local path=$1
    REPLY=$(basename -- "$path")
}

file_exists() {
    local path=$1
    [[ -e $path ]]
}

number_text() {
    local number=$1
    REPLY=$number
}

json_read() {
    local path=$1
    REPLY=$(jq -c . "$path") || koine_fail "cannot read $path"
}

console_warn() {
    local message=$1
    if [[ -t 1 ]]; then printf '\e[33m%s\e[0m\n' "$message"; else printf '%s\n' "$message"; fi
}

get_none() { reply=(); return; }

get_two() { reply=(x y); return; }

join_both() {
    local -a xs=("${@:2:$1}")
    shift $(($1 + 1))
    local sep=$1
    shift 1
    local -a ys=("${@:2:$1}")
    local koine_1 koine_2
    list_join ${#xs[@]} "${xs[@]}" "$sep"
    koine_1=$REPLY
    list_join ${#ys[@]} "${ys[@]}" "$sep"
    koine_2=$REPLY
    REPLY="$koine_1 | $koine_2"
    return
}

describe_point() {
    local p_x=$1 p_label=$2
    local koine_3
    number_text "$p_x"
    koine_3=$REPLY
    REPLY="$p_label at $koine_3"
    return
}

move_point() { local p_x=$1 p_label=$2 by=$3; reply_x=$by; reply_label=$p_label; return; }

test_big() { local n=$1; (( n > 2 )); return; }

m=()
m[B]=1
m[b]=2
printf '%s\n' "map: ${m[b]} ${m[B]}"
if [[ -n "${m[b]+set}" ]]; then printf '%s\n' 'has b: yes'; else printf '%s\n' 'has b: no'; fi
string_words '  a  b c '
words=("${reply[@]}")
list_join ${#words[@]} "${words[@]}" ,
koine_4=$REPLY
printf '%s\n' "words: ${words[1]} $koine_4"
string_take abcdef 3
koine_5=$REPLY
string_take ab 9
koine_6=$REPLY
printf '%s\n' "take: $koine_5 $koine_6"
if [[ '  # x' =~ $koine_re1 ]]; then printf '%s\n' 'comment: yes'; else printf '%s\n' 'comment: no'; fi
if [[ A == a ]]; then printf '%s\n' 'same case: yes'; else printf '%s\n' 'same case: no'; fi
if [[ abc == *"b"* ]]; then printf '%s\n' 'in string: yes'; else printf '%s\n' 'in string: no'; fi
if koine_7=0; for koine_8 in "${words[@]}"; do [[ $koine_8 == "c" ]] && { koine_7=1; break; }; done; (( koine_7 )); then
    printf '%s\n' 'in list: yes'
else
    printf '%s\n' 'in list: no'
fi
if koine_9=0; for koine_10 in "${words[@]}"; do [[ $koine_10 == "z" ]] && { koine_9=1; break; }; done; (( !koine_9 )); then
    printf '%s\n' 'not in list: yes'
else
    printf '%s\n' 'not in list: no'
fi
get_none
koine_12=("${reply[@]}")
list_join ${#koine_12[@]} "${koine_12[@]}" ,
koine_11=$REPLY
get_two
koine_14=("${reply[@]}")
list_join ${#koine_14[@]} "${koine_14[@]}" ,
koine_13=$REPLY
printf '%s\n' "lists: [$koine_11] [$koine_13]"
join_both 2 'a b' c ', ' 1 d
koine_15=$REPLY
printf '%s\n' "both: $koine_15"
p_x=3
p_label=here
describe_point "$p_x" "$p_label"
koine_16=$REPLY
printf '%s\n' "record: $koine_16"
move_point "$p_x" "$p_label" 7
q_x=$reply_x
q_label=$reply_label
number_text "$q_x"
koine_17=$REPLY
printf '%s\n' "moved: $koine_17 $q_label"
if test_big 3; then printf '%s\n' 'big: yes'; else printf '%s\n' 'big: no'; fi
json_read "$json"
koine_18=$REPLY
doc_name=$(jq -r '.name | values' <<< "$koine_18")
doc_count=$(jq -r '(.count | tonumber? | floor) // 0' <<< "$koine_18")
jq -e '.on == true' >/dev/null <<< "$koine_18" && doc_on=1 || doc_on=0
mapfile -t doc_tags < <(jq -r '.tags[]?' <<< "$koine_18")
number_text "$doc_count"
koine_19=$REPLY
list_join ${#doc_tags[@]} "${doc_tags[@]}" +
koine_20=$REPLY
printf '%s\n' "data: $doc_name $koine_19 $koine_20"
if (( doc_on )); then printf '%s\n' 'on: yes'; else printf '%s\n' 'on: no'; fi
path_join 3 a b c.txt
koine_22=$REPLY
path_name "$koine_22"
koine_21=$REPLY
path_join 3 a b c.txt
koine_25=$REPLY
path_parent "$koine_25"
koine_24=$REPLY
path_name "$koine_24"
koine_23=$REPLY
printf '%s\n' "path: $koine_21 $koine_23"
if file_exists "$json"; then printf '%s\n' 'exists: yes'; else printf '%s\n' 'exists: no'; fi
if git --version >/dev/null 2>&1; then printf '%s\n' 'probe git: yes'; else printf '%s\n' 'probe git: no'; fi
if git no-such-subcommand >/dev/null 2>&1; then
    printf '%s\n' 'failing git: yes'
else
    printf '%s\n' 'failing git: no'
fi
if no-such-program-koine >/dev/null 2>&1; then
    printf '%s\n' 'missing program: yes'
else
    printf '%s\n' 'missing program: no'
fi
lines=0
while IFS= read -r -u 3 line || [[ -n "$line" ]]; do lines=1; done 3< <(git --version)
wait $!
status=$?
printf '%s\n' "lines: $lines status $status"
version=$(git --version) || exit $?
string_take "$version" 11
koine_26=$REPLY
printf '%s\n' "capture: $koine_26"
other=$(git config --get koine.no-such-key) || other=fallback
printf '%s\n' "failed capture: $other"
n=0
while (( n < 3 )); do n=3; done
printf '%s\n' "while: $n"
koine_cleanups+=(koine_cleanup_1)
printf '%s\n' 'in the body'
unset 'koine_cleanups[-1]'
koine_cleanup_1
console_warn warned
git --version >/dev/null 2>&1 || exit $?
koine_cleanups+=(koine_cleanup_2)
koine_fail 'on purpose'
unset 'koine_cleanups[-1]'
koine_cleanup_2
printf '%s\n' never
