# DevShell current directory reporting for interactive Bash and Zsh.
# Source this from ~/.bashrc or ~/.zshrc on the SSH host.
case $- in *i*) ;; *) return 0 ;; esac
[ -n "${BASH_VERSION-}${ZSH_VERSION-}" ] || return 0
[ "${__devshell_cwd_installed-}" != 1 ] || return 0

__devshell_report_cwd() {
    local previous_status=$?
    # Encode UTF-8 bytes, including spaces, literal '+', '%' and terminal control bytes.
    local LC_ALL=C
    local directory=$PWD encoded= character hex index
    for ((index = 0; index < ${#directory}; index++)); do
        character=${directory:$index:1}
        case $character in
            [a-zA-Z0-9/._~-]) encoded+=$character ;;
            *) printf -v hex '%%%02X' "'$character"; encoded+=$hex ;;
        esac
    done
    # An empty URI authority keeps this independent of SSH aliases and hostname spelling.
    printf '\033]7;file://%s\007' "$encoded"
    return "$previous_status"
}

if [ -n "${BASH_VERSION-}" ]; then
    # Report after hooks that may change directory, without changing their exit status.
    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a '* ]]; then
        PROMPT_COMMAND=("${PROMPT_COMMAND[@]}" __devshell_report_cwd)
    else
        # A newline also works when the existing command ends in a shell comment.
        PROMPT_COMMAND="${PROMPT_COMMAND-}"$'\n__devshell_report_cwd'
    fi
else
    # Zsh runs these after precmd; existing hooks and the visible prompt stay intact.
    precmd_functions+=(__devshell_report_cwd)
fi
__devshell_cwd_installed=1
