package com.jack.micbridge.mic

/**
 * Shell fragment used by every Root fail-safe path. Microphone sensor privacy is scoped by
 * Android user, so a BLOCK boundary is valid only after every installed user plus the current
 * foreground user has been enumerated, blocked, and independently found in dumpsys readback.
 *
 * The fragment expects USER_ID, BLOCK_VERIFIED, and LAST to exist in the surrounding shell.
 */
internal object SensorPrivacyRootProtocol {
    const val COMMAND_TIMEOUT = "timeout -k 0.1 0.5"

    fun prerequisites(): String =
        "command -v cmd >/dev/null 2>&1 && command -v dumpsys >/dev/null 2>&1 && " +
            "command -v pm >/dev/null 2>&1 && command -v am >/dev/null 2>&1 && " +
            "command -v grep >/dev/null 2>&1 && command -v awk >/dev/null 2>&1"

    fun discoverUsersAttempt(commandTimeout: String = COMMAND_TIMEOUT): String = """
        USER_CONTEXT_READY=1
        USER_LIST_RAW=
        CURRENT_USER=
        if ! USER_LIST_RAW=${'$'}($commandTimeout pm list users 2>/dev/null); then
          USER_CONTEXT_READY=0
          LAST=user-list-failed
        fi
        if ! CURRENT_USER=${'$'}($commandTimeout am get-current-user 2>/dev/null); then
          USER_CONTEXT_READY=0
          LAST=current-user-failed
        fi
        case "${'$'}CURRENT_USER" in
          ''|*[!0-9]*) USER_CONTEXT_READY=0; LAST=current-user-invalid ;;
        esac
        if [ "${'$'}USER_CONTEXT_READY" = 1 ]; then
          USER_RECORD_COUNT=${'$'}(printf '%s\n' "${'$'}USER_LIST_RAW" | awk '/UserInfo/ {count++} END {print count+0}')
          USER_IDS=${'$'}(printf '%s\n' "${'$'}USER_LIST_RAW" | awk -F'[{}:]' '/UserInfo\{[0-9]+:/ { if (${'$'}2 ~ /^[0-9]+${'$'}/ && !seen[${'$'}2]++) print ${'$'}2 }')
          PARSED_USER_COUNT=${'$'}(printf '%s\n' "${'$'}USER_IDS" | awk '/^[0-9]+${'$'}/ {count++} END {print count+0}')
          CURRENT_USER_LISTED=0
          for MB_DISCOVERED_USER in ${'$'}USER_IDS; do
            if [ "${'$'}MB_DISCOVERED_USER" = "${'$'}CURRENT_USER" ]; then
              CURRENT_USER_LISTED=1
            fi
          done
          # Do not let appending the foreground id disguise an exit-0 but unrecognized/truncated
          # `pm list users` response. Every advertised UserInfo row must parse uniquely and the
          # independently queried foreground user must already be in that authoritative list.
          if [ "${'$'}USER_RECORD_COUNT" -le 0 ] ||
             [ "${'$'}PARSED_USER_COUNT" -ne "${'$'}USER_RECORD_COUNT" ] ||
             [ "${'$'}CURRENT_USER_LISTED" != 1 ]; then
            USER_CONTEXT_READY=0
            LAST=user-list-unverified
          fi
        fi
    """.trimIndent()

    fun blockAllUsersAttempt(commandTimeout: String = COMMAND_TIMEOUT): String = """
        BLOCK_VERIFIED=0
        ${discoverUsersAttempt(commandTimeout)}
        if [ "${'$'}USER_CONTEXT_READY" = 1 ]; then
          TARGET_FOUND=0
          COMMANDS_OK=1
          for MB_USER in ${'$'}USER_IDS; do
            case "${'$'}MB_USER" in
              ''|*[!0-9]*) COMMANDS_OK=0; LAST=user-id-invalid; continue ;;
            esac
            if [ "${'$'}MB_USER" = "${'$'}USER_ID" ]; then
              TARGET_FOUND=1
            fi
            if ! $commandTimeout cmd sensor_privacy enable "${'$'}MB_USER" microphone >/dev/null 2>&1; then
              COMMANDS_OK=0
              LAST=command-failed
            fi
          done
          if [ "${'$'}TARGET_FOUND" != 1 ]; then
            COMMANDS_OK=0
            LAST=target-user-missing
          fi
          if [ "${'$'}COMMANDS_OK" = 1 ]; then
            if OUT=${'$'}($commandTimeout dumpsys sensor_privacy 2>/dev/null); then
              READBACK_OK=1
              for MB_USER in ${'$'}USER_IDS; do
                if ! { ${blockedReadback("OUT", "MB_USER")}; }; then
                  READBACK_OK=0
                  LAST=readback-failed
                fi
              done
              if [ "${'$'}READBACK_OK" = 1 ]; then
                BLOCK_VERIFIED=1
              fi
            else
              LAST=readback-command-failed
            fi
          fi
        fi
    """.trimIndent()

    fun blockedReadback(
        outputVariable: String,
        userVariable: String,
    ): String =
        "printf '%s\\n' \"${'$'}$outputVariable\" | awk -v wanted=\"${'$'}$userVariable\" '" +
            "function num(s){sub(/^[^=]*=/,\"\",s);sub(/^[[:space:]]+/,\"\",s);" +
            "sub(/[[:space:]]+${'$'}/,\"\",s);" +
            "if(s!~/^[0-9]+${'$'}/)return -1;return s+0} " +
            "BEGIN{depth=0;user=-1;user_depth=-1;sensor=-1;sensor_depth=-1;" +
            "global_enabled=-1;global_conflict=0;invalid_scope=0} " +
            "{line=${'$'}0;gsub(/^[[:space:]]+|[[:space:]]+${'$'}/,\"\",line);" +
            "while(substr(line,1,1)==\"}\"){depth--;if(depth<0){invalid_scope=1;depth=0};" +
            "if(sensor_depth>depth){sensor=-1;sensor_depth=-1};" +
            "if(user_depth>depth){user=-1;user_depth=-1;sensor=-1;sensor_depth=-1};" +
            "sub(/^}[[:space:]]*/,\"\",line)};" +
            "if(line~/^user_id[[:space:]]*=/){user=num(line);user_depth=depth;sensor=-1;sensor_depth=-1}" +
            "else if(line~/^sensor[[:space:]]*=/){sensor=num(line);sensor_depth=depth}" +
            "else if(line~/^state_type[[:space:]]*=/ && user==wanted && sensor==1 && num(line)==1){found=1}" +
            "else if(line~/^is_enabled[[:space:]]*=/){" +
            "enabled=(line~/^is_enabled[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}/)?1:" +
            "((line~/^is_enabled[[:space:]]*=[[:space:]]*false[[:space:]]*${'$'}/)?0:-1);" +
            "if(user==wanted && sensor==1 && enabled==1){found=1}" +
            "else if(user==-1 && sensor==-1 && depth<=1 && enabled>=0){" +
            "if(global_enabled>=0 && global_enabled!=enabled)global_conflict=1;global_enabled=enabled}};" +
            "shape=line;opens=gsub(/\\{/,\"\",shape);shape=line;closes=gsub(/\\}/,\"\",shape);" +
            "depth+=opens-closes;if(depth<0){invalid_scope=1;depth=0};" +
            "if(sensor_depth>depth){sensor=-1;sensor_depth=-1};" +
            "if(user_depth>depth){user=-1;user_depth=-1;sensor=-1;sensor_depth=-1}} " +
            "END{exit(!invalid_scope&&!global_conflict&&(global_enabled==1||found)?0:1)}'"
}
