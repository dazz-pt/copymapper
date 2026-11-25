package pt.dazz.copymapper.copybook.map;

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import pt.dazz.copymapper.copybook.ast.DataItem;

/**
 * Applies the Java names that a mapping spec gives to items. The Java name of a record, a
 * group with a class, a table, or an alternative is the class name. The Java name of another
 * item is the field name. These Java names are errors: a name that is not a Java identifier,
 * a name on a FILLER, a class name that would shadow a java.lang class, and a name that the
 * mapper does not use.
 */
final class JavaNames {

    /** The restricted identifiers that a class must not have as its name. */
    private static final Set<String> RESTRICTED = Set.of("var", "yield", "record", "sealed",
        "permits");
    private static final Comparator<DataItem> SOURCE_ORDER = Comparator.comparing(
            DataItem::source, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
        .thenComparingInt(DataItem::line).thenComparingInt(DataItem::column);

    private final ModelMapper mapper;
    private final Map<DataItem, String> byItem;
    private final Set<DataItem> used = Collections.newSetFromMap(new IdentityHashMap<>());

    JavaNames(ModelMapper mapper, Map<DataItem, String> byItem) {
        this.mapper = mapper;
        this.byItem = new IdentityHashMap<>(byItem);
    }

    /**
     * Reports each Java name that is not a Java identifier or that is on a FILLER, in source
     * order. The mapper does not use these names.
     */
    void check() {
        byItem.keySet().stream().sorted(SOURCE_ORDER).toList().forEach(item -> {
            String name = byItem.get(item);
            if (item.isFiller()) {
                mapper.error(item, "the Java name " + name + " is on a FILLER. The generator"
                    + " gives no Java name to a FILLER.");
                byItem.remove(item);
            } else if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                mapper.error(item, "the Java name " + name + " of " + item.name()
                    + " is not a Java identifier");
                byItem.remove(item);
            }
        });
    }

    /** Gives the Java name of an item, or {@code null}, and marks the name as used. */
    String take(DataItem item) {
        String name = byItem.get(item);
        if (name != null) {
            used.add(item);
        }
        return name;
    }

    /**
     * Gives the class name of an item. A Java name of the item replaces the derived name. A
     * Java name that another class or enum has is an error.
     *
     * @param base the derived name, which gets a prefix or a number if it is in use
     * @param parent the name of the enclosing class, or {@code null}
     */
    String className(DataItem item, String base, String parent) {
        String explicit = take(item);
        if (explicit == null) {
            return mapper.uniqueClassName(base, parent);
        }
        if (Names.JAVA_LANG.contains(explicit)) {
            mapper.error(item, "the Java name " + explicit + " of " + item.name()
                + " is the name of a java.lang class");
            return mapper.uniqueClassName(base, parent);
        }
        if (RESTRICTED.contains(explicit)) {
            mapper.error(item, "the Java name " + explicit + " of " + item.name()
                + " is a restricted name and cannot be a class name");
            return mapper.uniqueClassName(base, parent);
        }
        if (!mapper.classNames.add(explicit.toLowerCase(Locale.ROOT))) {
            mapper.error(item, "the Java name " + explicit + " of " + item.name()
                + " is already the name of another class or enum");
            return mapper.uniqueClassName(explicit, parent);
        }
        return explicit;
    }

    /**
     * Gives an explicit field name in a class. A name that the class already has is an error.
     *
     * @param explicit the Java name of the item
     * @param groups the flattened groups in the class, from outer to inner
     */
    String fieldName(DataItem item, String explicit, ModelMapper.ClassBuilder owner,
                     List<DataItem> groups) {
        if (owner.used.add(explicit)) {
            return explicit;
        }
        mapper.error(item, "the Java name " + explicit + " of " + item.name()
            + " is already used in class " + owner.name);
        return owner.fieldName(explicit, groups);
    }

    /** Reports each Java name that the mapper did not use, in source order. */
    void checkUnused() {
        byItem.keySet().stream().filter(item -> !used.contains(item))
            .sorted(SOURCE_ORDER)
            .forEach(item -> mapper.error(item, "the Java name " + byItem.get(item) + " of "
                + item.name() + " is not used. The generator makes no class or field for "
                + item.name() + "."));
    }
}
