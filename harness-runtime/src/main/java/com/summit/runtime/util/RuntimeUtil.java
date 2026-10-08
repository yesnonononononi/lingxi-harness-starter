package com.summit.runtime.util;

public class RuntimeUtil {
    public static Throwable runAll(Runnable... tasks) {
        Throwable primary = null;
        for (Runnable task : tasks) {
            try {
                task.run();
            } catch (Throwable t) {
                if (primary == null) primary = t;
                else if (primary != t) primary.addSuppressed(t);
            }
        }
        return primary;
    }
}
