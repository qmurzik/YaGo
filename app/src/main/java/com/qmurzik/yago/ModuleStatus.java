package com.qmurzik.yago;

/** Метод подменяется хуком в процессе самого модуля, если модуль активен в LSPosed. */
public final class ModuleStatus {
    private ModuleStatus() {}

    public static boolean isActive() {
        return false;
    }
}
