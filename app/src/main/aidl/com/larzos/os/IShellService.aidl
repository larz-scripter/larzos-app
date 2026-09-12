package com.larzos.os;

/**
 * Runs in a process Shizuku spawns at shell (or root) UID - see
 * ShellUserService, the implementation. exec() itself needs no privileged
 * API at all: the whole process it runs in already has that UID, so a
 * plain ProcessBuilder inherits it.
 */
interface IShellService {
    /** Returns "<exitCode>\n<combined stdout+stderr>". */
    String exec(String cmdline);
}
