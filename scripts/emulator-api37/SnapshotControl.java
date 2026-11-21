import java.lang.reflect.Method;

/** Calls the platform switch without linking against internal Android classes. */
public final class SnapshotControl {
    public static void main(String[] args) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "window");
        Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
        Method asInterface = null;
        for (Method method : stub.getDeclaredMethods()) {
            if (method.getName().equals("asInterface")) {
                asInterface = method;
                break;
            }
        }
        if (asInterface == null) {
            throw new IllegalStateException("asInterface not found");
        }
        Object window = asInterface.invoke(null, binder);
        Method setter = null;
        for (Method method : Class.forName("android.view.IWindowManager").getMethods()) {
            if (method.getName().equals("setTaskSnapshotEnabled")) {
                setter = method;
                break;
            }
        }
        if (setter == null) {
            throw new IllegalStateException("setTaskSnapshotEnabled not found");
        }
        setter.invoke(window, false);
        System.out.println("task snapshots disabled");
    }
}
