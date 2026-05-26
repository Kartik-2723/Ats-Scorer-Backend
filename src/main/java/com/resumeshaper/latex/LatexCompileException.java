package com.resumeshaper.latex;

import lombok.Getter;

@Getter
public class LatexCompileException extends RuntimeException {

    private final String compilerLog;

    public LatexCompileException(String message, String compilerLog) {
        super(message);
        this.compilerLog = compilerLog;
    }
}