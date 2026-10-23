package sibarum.koine.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The modules that ship with the compiler, in {@code sibarum/koine/std}. Every member is native tier and has a body
 * for every OS, unless it says otherwise.
 */
public final class StandardLibrary implements Library {

    public static final StandardLibrary INSTANCE = new StandardLibrary();

    /** Every module in it, for the tests that check them all. */
    public static final List<String> MODULES = List.of("Clock", "Console", "Dir", "File", "Home", "Json", "List",
            "Number", "Path", "Process", "Python", "Script", "String");

    private StandardLibrary() {
    }

    @Override
    public Optional<String> module(String name) {
        try (InputStream in = StandardLibrary.class.getResourceAsStream("/sibarum/koine/std/" + name + ".koine")) {
            if (in == null) return Optional.empty();
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
