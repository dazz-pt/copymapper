package pt.dazz.copymapper.copybook.spec;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.ast.DataItem;
import pt.dazz.copymapper.copybook.diag.Diagnostic;
import pt.dazz.copymapper.copybook.diag.Severity;
import pt.dazz.copymapper.copybook.map.MappingOptions;

/**
 * The generator inputs that a mapping spec gives.
 *
 * @param copybook the copybook, with one record
 * @param options the mapping options
 * @param javaNames the Java names, by item of the copybook (identity keys)
 * @param diagnostics the problems of the translation
 */
public record Translation(Copybook copybook, MappingOptions options,
        Map<DataItem, String> javaNames, List<Diagnostic> diagnostics) {

    /** Copies the Java names with identity keys, and copies the diagnostics. */
    public Translation {
        javaNames = Collections.unmodifiableMap(new IdentityHashMap<>(javaNames));
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Tells if the translation has an error.
     *
     * @return true if a diagnostic is an error
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
