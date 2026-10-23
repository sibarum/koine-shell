#!/usr/bin/env zsh
emulate -L zsh

json=
while (( $# )); do
    case $1 in
        --json) json=${2:?--json needs a value}; shift 2 ;;
        -h|--help)
            cat <<'EOF'
Usage: probe.zsh [--json JSON]

Tries each portable construct.

Options:
  --json JSON   A JSON file to read.
  -h, --help    Show this help.
EOF
            exit 0
            ;;
        *) print -u2 -r -- "unknown option: $1 (--help lists them)"; exit 2 ;;
    esac
done

# Fails the script: the message in red, with the step it is in, then exit, which runs the cleanup.
koine_fail() {
    local message=$1
    if [[ -n ${koine_step-} ]]; then message="$koine_step failed: $message"; fi
    if [[ -t 2 ]]; then print -u2 -r -- $'\e[31m'"$message"$'\e[0m'; else print -u2 -r -- "$message"; fi
    exit 1
}

# The cleanup of each finally still open, run innermost first however the script ends.
koine_cleanups=()
koine_run_cleanups() {
    local i
    for (( i = ${#koine_cleanups}; i >= 1; i-- )); do "${koine_cleanups[i]}"; done
}
trap koine_run_cleanups EXIT
koine_cleanup_1() { print -r -- 'cleaned up'; }
koine_cleanup_2() { print -r -- 'cleaned up after the failure'; }

String_words() {
    emulate -L zsh
    local -h text=$1
    reply=(${=text})
}

String_take() {
    emulate -L zsh
    local -h text=$1 count=$2
    REPLY=${text:0:count}
}

List_join() {
    emulate -L zsh
    local -h -a items
    items=("${@[2,$1+1]}")
    shift $(($1 + 1))
    local -h separator=$1
    REPLY=${(pj:$separator:)items}
}

Path_join() {
    emulate -L zsh
    local -h -a parts
    parts=("${@[2,$1+1]}")
    local IFS=/
    REPLY="${parts[*]}"
}

Path_parent() {
    emulate -L zsh
    local -h path=$1
    REPLY=${path:h}
}

Path_name() {
    emulate -L zsh
    local -h path=$1
    REPLY=${path:t}
}

File_exists() {
    emulate -L zsh
    local -h path=$1
    [[ -e $path ]]
}

Number_text() {
    emulate -L zsh
    local -h number=$1
    REPLY=$number
}

Json_read() {
    emulate -L zsh
    local -h path=$1
    REPLY=$(jq -c . "$path") || koine_fail "cannot read $path"
}

Console_warn() {
    emulate -L zsh
    local -h message=$1
    if [[ -t 1 ]]; then printf '\e[33m%s\e[0m\n' "$message"; else printf '%s\n' "$message"; fi
}

Get-None() { emulate -L zsh; reply=(); return; }

Get-Two() { emulate -L zsh; reply=(x y); return; }

Join-Both() {
    emulate -L zsh
    local -a xs
    xs=("${@[2,$1+1]}")
    shift $(($1 + 1))
    local sep=$1
    shift 1
    local -a ys
    ys=("${@[2,$1+1]}")
    local koineTemp1 koineTemp2
    List_join ${#xs} "${xs[@]}" "$sep"
    koineTemp1=$REPLY
    List_join ${#ys} "${ys[@]}" "$sep"
    koineTemp2=$REPLY
    REPLY="$koineTemp1 | $koineTemp2"
    return
}

Describe-Point() {
    emulate -L zsh
    local p_x=$1 p_label=$2
    local koineTemp3
    Number_text "$p_x"
    koineTemp3=$REPLY
    REPLY="$p_label at $koineTemp3"
    return
}

Move-Point() { emulate -L zsh; local p_x=$1 p_label=$2 by=$3; reply_x=$by; reply_label=$p_label; return; }

Test-Big() { emulate -L zsh; local n=$1; (( n > 2 )); return; }

typeset -A m
m=()
m[B]=1
m[b]=2
print -r -- "map: ${m[b]} ${m[B]}"
if (( ${+m[b]} )); then print -r -- 'has b: yes'; else print -r -- 'has b: no'; fi
String_words '  a  b c '
words=("${reply[@]}")
List_join ${#words} "${words[@]}" ,
koineTemp4=$REPLY
print -r -- "words: ${words[2]} $koineTemp4"
String_take abcdef 3
koineTemp5=$REPLY
String_take ab 9
koineTemp6=$REPLY
print -r -- "take: $koineTemp5 $koineTemp6"
if [[ '  # x' =~ '^[[:space:]]*(#|$)' ]]; then print -r -- 'comment: yes'; else print -r -- 'comment: no'; fi
if [[ A == a ]]; then print -r -- 'same case: yes'; else print -r -- 'same case: no'; fi
if [[ abc == *b* ]]; then print -r -- 'in string: yes'; else print -r -- 'in string: no'; fi
if koineTemp7=c; (( ${words[(Ie)$koineTemp7]} )); then print -r -- 'in list: yes'; else print -r -- 'in list: no'; fi
if ! { koineTemp8=z; (( ${words[(Ie)$koineTemp8]} )); }; then
    print -r -- 'not in list: yes'
else
    print -r -- 'not in list: no'
fi
Get-None
koineTemp10=("${reply[@]}")
List_join ${#koineTemp10} "${koineTemp10[@]}" ,
koineTemp9=$REPLY
Get-Two
koineTemp12=("${reply[@]}")
List_join ${#koineTemp12} "${koineTemp12[@]}" ,
koineTemp11=$REPLY
print -r -- "lists: [$koineTemp9] [$koineTemp11]"
Join-Both 2 'a b' c ', ' 1 d
koineTemp13=$REPLY
print -r -- "both: $koineTemp13"
p_x=3
p_label=here
Describe-Point "$p_x" "$p_label"
koineTemp14=$REPLY
print -r -- "record: $koineTemp14"
Move-Point "$p_x" "$p_label" 7
q_x=$reply_x
q_label=$reply_label
Number_text "$q_x"
koineTemp15=$REPLY
print -r -- "moved: $koineTemp15 $q_label"
if Test-Big 3; then print -r -- 'big: yes'; else print -r -- 'big: no'; fi
Json_read "$json"
koineTemp16=$REPLY
doc_name=$(jq -r '.name | values' <<< $koineTemp16)
doc_count=$(jq -r '(.count | tonumber? | floor) // 0' <<< $koineTemp16)
jq -e '.on == true' >/dev/null <<< $koineTemp16 && doc_on=1 || doc_on=0
doc_tags=(${(f)"$(jq -r '.tags[]?' <<< $koineTemp16)"})
Number_text "$doc_count"
koineTemp17=$REPLY
List_join ${#doc_tags} "${doc_tags[@]}" +
koineTemp18=$REPLY
print -r -- "data: $doc_name $koineTemp17 $koineTemp18"
if (( doc_on )); then print -r -- 'on: yes'; else print -r -- 'on: no'; fi
Path_join 3 a b c.txt
koineTemp20=$REPLY
Path_name "$koineTemp20"
koineTemp19=$REPLY
Path_join 3 a b c.txt
koineTemp23=$REPLY
Path_parent "$koineTemp23"
koineTemp22=$REPLY
Path_name "$koineTemp22"
koineTemp21=$REPLY
print -r -- "path: $koineTemp19 $koineTemp21"
if File_exists "$json"; then print -r -- 'exists: yes'; else print -r -- 'exists: no'; fi
if git --version &>/dev/null; then print -r -- 'probe git: yes'; else print -r -- 'probe git: no'; fi
if git no-such-subcommand &>/dev/null; then print -r -- 'failing git: yes'; else print -r -- 'failing git: no'; fi
if no-such-program-koine &>/dev/null; then
    print -r -- 'missing program: yes'
else
    print -r -- 'missing program: no'
fi
lines=0
git --version | while IFS= read -r line || [[ -n $line ]]; do lines=1; done
status_=${pipestatus[1]}
print -r -- "lines: $lines status $status_"
version=$(git --version) || exit $?
String_take "$version" 11
koineTemp24=$REPLY
print -r -- "capture: $koineTemp24"
other=$(git config --get koine.no-such-key) || other=fallback
print -r -- "failed capture: $other"
n=0
while (( n < 3 )); do n=3; done
print -r -- "while: $n"
koine_cleanups+=(koine_cleanup_1)
print -r -- 'in the body'
shift -p koine_cleanups
koine_cleanup_1
Console_warn warned
git --version &>/dev/null || exit $?
koine_cleanups+=(koine_cleanup_2)
koine_fail 'on purpose'
shift -p koine_cleanups
koine_cleanup_2
print -r -- never
