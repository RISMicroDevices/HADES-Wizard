package cn.rismd.hades.wizard.core.backend;

public record Diagnostic(String severity, String code, String path, String message) {
    @Override
    public String toString() {
        return severity.toUpperCase() + " " + code + " " + path + ": " + message;
    }
}
