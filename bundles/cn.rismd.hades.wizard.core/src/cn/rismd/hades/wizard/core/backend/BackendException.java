package cn.rismd.hades.wizard.core.backend;

import java.util.List;

public class BackendException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final List<Diagnostic> diagnostics;

    public BackendException(String code, String message) {
        this(code, message, List.of(), null);
    }

    public BackendException(String code, String message, Throwable cause) {
        this(code, message, List.of(), cause);
    }

    public BackendException(String code, String message, List<Diagnostic> diagnostics) {
        this(code, message, diagnostics, null);
    }

    private BackendException(String code, String message, List<Diagnostic> diagnostics, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String code() {
        return code;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
