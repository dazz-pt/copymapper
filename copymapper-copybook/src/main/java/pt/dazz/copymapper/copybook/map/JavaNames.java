package pt.dazz.copymapper.copybook.map;

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pt.dazz.copymapper.copybook.ast.DataItem;

/**
 * Applies the Java names that a mapping spec gives to items. The Java name of a record, a
 * group with a class, a table, or an alternative is the class name. The Java name of another
 * item is the field name. A Java name that the mapper does not use is an error.
 */
final class JavaNames {

    private final ModelMapper mapper;
    private final Map<DataItem, String> byItem;
    private final Set<DataItem> used = Collections.newSetFromMap(new IdentityHashMap<>());

    JavaNames(ModelMapper mapper, Map<DataItem, String> byItem) {
        this.mapper = mapper;
        this.byItem = new IdentityHashMap<>(byItem);
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
            .sorted(Comparator.comparing(DataItem::source,
                    Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                .thenComparingInt(DataItem::line).thenComparingInt(DataItem::column))
            .forEach(item -> mapper.error(item, "the Java name " + byItem.get(item) + " of "
                + item.name() + " is not used. The generator makes no class or field for "
                + item.name() + "."));
    }
}
