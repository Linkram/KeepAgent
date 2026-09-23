package io.keepagent.app.runtime;

interface LocalRuntimeService {
    String info();
    String runPython(String requestJson, String workspacePath);
    String runJava(String requestJson, String workspacePath);
    void cancel();
}
