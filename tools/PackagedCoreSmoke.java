import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import io.ktor.client.HttpClientEngineContainer;

import java.nio.file.Path;
import java.util.ServiceLoader;

/** Проверяет release-runtime без запуска графического интерфейса. */
public final class PackagedCoreSmoke {
    private PackagedCoreSmoke() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("ожидался путь к wolfy_core.dll");
        }

        var library = NativeLibrary.getInstance(Path.of(args[0]).toAbsolutePath().toString());
        try {
            Pointer version = library.getFunction("wolfy_version").invokePointer(new Object[0]);
            if (version == null) {
                throw new IllegalStateException("wolfy_version вернул null");
            }
            System.out.println("core=" + version.getString(0, "UTF-8"));
        } finally {
            // Именно закрытие вызывало прежний сбой Native.dispose в release.
            library.close();
        }

        var engines = ServiceLoader.load(HttpClientEngineContainer.class).stream().toList();
        if (engines.isEmpty()) {
            throw new IllegalStateException("Ktor не нашёл сетевой движок");
        }
        System.out.println("http=" + engines.get(0).type().getName());
    }
}
