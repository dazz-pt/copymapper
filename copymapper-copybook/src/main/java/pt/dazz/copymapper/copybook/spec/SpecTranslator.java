package pt.dazz.copymapper.copybook.spec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import pt.dazz.copymapper.annotation.Direction;
import pt.dazz.copymapper.cobol.Sign;
import pt.dazz.copymapper.copybook.ast.Condition;
import pt.dazz.copymapper.copybook.ast.ConditionValue;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.ast.DataItem;
import pt.dazz.copymapper.copybook.ast.Literal;
import pt.dazz.copymapper.copybook.ast.Occurs;
import pt.dazz.copymapper.copybook.ast.Picture;
import pt.dazz.copymapper.copybook.ast.QualifiedName;
import pt.dazz.copymapper.copybook.ast.Usage;
import pt.dazz.copymapper.copybook.diag.Diagnostics;
import pt.dazz.copymapper.copybook.map.ItemIndex;
import pt.dazz.copymapper.copybook.map.MappingOptions;
import pt.dazz.copymapper.copybook.map.SubTypeDecision;

/**
 * Translates a {@link MappingSpec} to the inputs of the copybook generator: a copybook with
 * one record, the mapping options, and the Java names.
 */
public final class SpecTranslator {

    private static final String FILLER = "FILLER";
    private static final String TWO_ALTERNATIVES =
            " The generator needs two different alternatives.";
    private static final Set<JsonType> DATE_TYPES =
            Set.of(JsonType.DATE, JsonType.TIME, JsonType.DATE_TIME);

    private SpecTranslator() {
    }

    /**
     * Translates a mapping spec. A REST decision that the generator cannot express gives an
     * error diagnostic. The name markers of the options are empty, so only the spec gives
     * directions.
     *
     * @param spec the mapping spec
     * @return the generator inputs and the diagnostics
     */
    public static Translation translate(MappingSpec spec) {
        return new Run(spec).translate();
    }

    /** The state of one translation. */
    private static final class Run {

        private final MappingSpec spec;
        private final Diagnostics diagnostics = new Diagnostics();
        private final Map<String, SpecNode> byPath = new HashMap<>();
        private final Map<SpecNode, SpecNode> parents = new IdentityHashMap<>();
        private final Map<SpecNode, DataItem> items = new IdentityHashMap<>();
        private final Map<DataItem, String> javaNames = new IdentityHashMap<>();
        private final Map<String, Direction> directions = new LinkedHashMap<>();
        private final Map<String, SubTypeDecision> subTypes = new LinkedHashMap<>();
        private final Map<String, String> countFields = new LinkedHashMap<>();
        private final Map<String, String> dates = new LinkedHashMap<>();
        private final Map<String, String> emptyItems = new LinkedHashMap<>();
        private final Map<String, String> emptyKeys = new LinkedHashMap<>();
        private ItemIndex index;

        Run(MappingSpec spec) {
            this.spec = spec;
        }

        Translation translate() {
            index(spec.root(), null);
            // A copy of the record with the names only, to find the COBOL names.
            index = new ItemIndex(new Copybook(spec.sourceName(), List.of(shape(spec.root()))));
            Copybook copybook = new Copybook(spec.sourceName(),
                    List.of(build(spec.root(), Usage.DISPLAY)));
            decide(spec.root(), false);
            MappingOptions options = new MappingOptions(List.of(), List.of(), List.of(),
                    directions, subTypes, countFields, null, dates, Map.of(), Map.of(),
                    Map.of(), emptyItems, emptyKeys, List.of(), List.of());
            return new Translation(copybook, options, javaNames, diagnostics.all());
        }

        private void index(SpecNode node, SpecNode parent) {
            byPath.put(node.path(), node);
            if (parent != null) {
                parents.put(node, parent);
            }
            node.children().forEach(child -> index(child, node));
        }

        private DataItem shape(SpecNode node) {
            List<DataItem> children = node.children().stream().map(this::shape).toList();
            return new DataItem(node.level(), upper(node.cobolName()), null, null, null, null,
                    List.of(), children, spec.sourceName(), 1, 1, null, null, null);
        }

        // ---- the COBOL side ----

        private DataItem build(SpecNode node, Usage inherited) {
            Usage usage = node.usage() != null ? node.usage() : inherited;
            List<DataItem> children = new ArrayList<>();
            for (SpecNode child : node.children()) {
                children.add(build(child, usage));
            }
            SourceLocation at = location(node);
            Picture picture = picture(node);
            List<Condition> conditions = node.conditions().stream()
                    .map(c -> condition(c, at)).toList();
            DataItem item = new DataItem(node.level(), upper(node.cobolName()), picture,
                    node.usage(), occurs(node), redefines(node.redefines()), conditions,
                    children, at.source(), at.line(), at.column(), node.declaration(),
                    node.value() == null ? null : literal(node.value()),
                    sign(node.sign(), picture, usage));
            items.put(node, item);
            return item;
        }

        private Picture picture(SpecNode node) {
            if (node.picture() == null) {
                return null;
            }
            try {
                Picture picture = Picture.parse(node.picture());
                return node.blankWhenZero() ? picture.withBlankWhenZero() : picture;
            } catch (IllegalArgumentException e) {
                error(node, "the picture " + node.picture() + " is not valid: "
                        + e.getMessage());
                return null;
            }
        }

        /** The draft writes TRAILING for the default sign, and the AST has null for it. */
        private static Sign sign(Sign sign, Picture picture, Usage usage) {
            boolean defaultSign = sign == Sign.TRAILING && picture != null
                    && picture.edited() == null && picture.signed() && usage == Usage.DISPLAY;
            return defaultSign ? null : sign;
        }

        private Occurs occurs(SpecNode node) {
            OccursSpec occurs = node.occurs();
            if (occurs == null) {
                return null;
            }
            String text = occurs.dependingOn();
            return new Occurs(occurs.min(), occurs.max(),
                    text == null ? null : dependingOn(node, text));
        }

        /** Names the counter with a COBOL name, or keeps the text that is not a path. */
        private QualifiedName dependingOn(SpecNode node, String text) {
            SpecNode target = byPath.get(text);
            if (target != null) {
                String name = nameOf(node, target);
                return name != null ? QualifiedName.parse(name) : redefines(text);
            }
            try {
                return QualifiedName.parse(text);
            } catch (IllegalArgumentException e) {
                error(node, "the DEPENDING ON item '" + text + "' is not a path of the spec"
                        + " and not a COBOL name");
                return new QualifiedName(List.of(text));
            }
        }

        /** A REDEFINES names a sibling, so the last path segment is enough. */
        private static QualifiedName redefines(String path) {
            if (path == null) {
                return null;
            }
            String segment = path.substring(path.lastIndexOf('.') + 1);
            return new QualifiedName(List.of(upper(segment.replaceFirst("#\\d+$", ""))));
        }

        private static Condition condition(ConditionSpec spec, SourceLocation at) {
            List<ConditionValue> values = spec.values().stream()
                    .map(r -> new ConditionValue(literal(r.from()),
                            r.thru() == null ? null : literal(r.thru())))
                    .toList();
            return new Condition(spec.name(), values, at.line(), at.column());
        }

        private static Literal literal(ValueSpec value) {
            return new Literal(Literal.Kind.valueOf(value.kind()), value.text());
        }

        // ---- the REST side ----

        private void decide(SpecNode node, boolean inFiller) {
            javaName(node, inFiller);
            if (node.format() != null && DATE_TYPES.contains(node.jsonType())) {
                String name = nameOf(node, node);
                if (name != null) {
                    dates.put(name, node.format());
                }
            }
            if (node.table() != null) {
                table(node, node.table());
            }
            if (node.variant() instanceof VariantSpec.Discriminator d) {
                discriminator(node, d);
            } else if (node.variant() instanceof VariantSpec.FixedSelection f) {
                fixedSelection(node, f);
            }
            boolean filler = inFiller || isFiller(node);
            node.children().forEach(child -> decide(child, filler));
        }

        private void table(SpecNode node, TableSpec table) {
            switch (table.mode()) {
                case COUNT -> pair(countFields, node, ref(node, table.path()));
                case KEY -> pair(emptyKeys, node, ref(node, table.path()));
                case SPACES -> {
                    String name = nameOf(node, node);
                    if (name != null) {
                        emptyItems.put(name, "SPACES");
                    }
                }
                case LOW_VALUES -> {
                    // The default of the generator.
                }
            }
        }

        /** Puts the COBOL names of a table and of its counter or key. */
        private void pair(Map<String, String> map, SpecNode table, SpecNode other) {
            if (other == null) {
                return;
            }
            String tableName = nameOf(table, table);
            String otherName = nameOf(table, other);
            if (tableName != null && otherName != null) {
                map.put(tableName, otherName);
            }
        }

        private void discriminator(SpecNode node, VariantSpec.Discriminator variant) {
            if (variant.values().isEmpty()) {
                error(node, "the discriminator has no values");
                return;
            }
            SpecNode field = ref(node, variant.path());
            boolean valid = field != null;
            Map<SpecNode, String> byAlternative = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : variant.values().entrySet()) {
                SpecNode alternative = ref(node, entry.getValue());
                if (alternative == null) {
                    valid = false;
                } else if (byAlternative.putIfAbsent(alternative, entry.getKey()) != null) {
                    error(node, "the discriminator gives the values "
                            + byAlternative.get(alternative) + " and " + entry.getKey()
                            + " to the alternative " + alternative.path()
                            + ". The generator needs one value for each alternative.");
                    valid = false;
                }
            }
            if (!valid) {
                return;
            }
            String discriminator = nameOf(node, field);
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> classNames = new LinkedHashMap<>();
            for (Map.Entry<SpecNode, String> entry : byAlternative.entrySet()) {
                String name = nameOf(node, entry.getKey());
                if (name == null) {
                    return;
                }
                values.put(name, entry.getValue());
                if (entry.getKey().javaName() != null) {
                    classNames.put(name, entry.getKey().javaName());
                }
            }
            if (discriminator != null) {
                subTypes.put(values.keySet().iterator().next(),
                        new SubTypeDecision(discriminator, values, classNames));
            }
        }

        private void fixedSelection(SpecNode node, VariantSpec.FixedSelection variant) {
            if (variant.request() == null || variant.response() == null) {
                error(node, "the fixed selection has no alternative for the "
                        + (variant.request() == null ? "request" : "response") + "."
                        + TWO_ALTERNATIVES);
                return;
            }
            if (variant.request().equals(variant.response())) {
                error(node, "the fixed selection gives the alternative " + variant.request()
                        + " to the request and to the response." + TWO_ALTERNATIVES);
                return;
            }
            SpecNode request = ref(node, variant.request());
            SpecNode response = ref(node, variant.response());
            if (request == null || response == null) {
                return;
            }
            String input = nameOf(node, request);
            String output = nameOf(node, response);
            if (input != null && output != null) {
                directions.put(input, Direction.INPUT);
                directions.put(output, Direction.OUTPUT);
            }
        }

        private void javaName(SpecNode node, boolean inFiller) {
            String name = node.javaName();
            if (name == null) {
                return;
            }
            String prefix = "the Java name " + name;
            if (isFiller(node)) {
                error(node, prefix + " is on a FILLER. The generator gives no Java name to a"
                        + " FILLER.");
            } else if (inFiller) {
                error(node, prefix + " is in a FILLER group. The generator gives no Java name"
                        + " to an item in a FILLER group.");
            } else if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                error(node, prefix + " is not a Java identifier");
            } else if (node.path().substring(node.path().lastIndexOf('.') + 1).contains("#")) {
                error(node, prefix + " is on a duplicate sibling name. Rename the COBOL item.");
            } else {
                javaNames.put(items.get(node), name);
            }
        }

        // ---- names ----

        private SpecNode ref(SpecNode owner, String path) {
            SpecNode node = byPath.get(path);
            if (node == null) {
                error(owner, "the path " + path + " is not in the spec");
            }
            return node;
        }

        /**
         * Gives a COBOL name that designates only the target: its name, then the names of
         * its groups outward, one by one, until the name is unique. A FILLER is not a
         * qualifier. The target
         * matches each of these names, so a unique match is the target. A FILLER target is
         * not in the index, and a duplicate sibling name stays ambiguous.
         *
         * @return the name, or null after an error on the owner
         */
        private String nameOf(SpecNode owner, SpecNode target) {
            List<String> parts = new ArrayList<>();
            parts.add(upper(target.cobolName()));
            for (SpecNode ancestor = target; ancestor != null; ancestor = parents.get(ancestor)) {
                if (ancestor != target && !isFiller(ancestor)) {
                    parts.add(upper(ancestor.cobolName()));
                }
                if (index.find(new QualifiedName(parts)) instanceof ItemIndex.Found) {
                    return String.join(" OF ", parts);
                }
            }
            error(owner, "no COBOL name designates only the item " + target.path()
                    + ". A FILLER or a duplicate sibling name cannot be named.");
            return null;
        }

        private static boolean isFiller(SpecNode node) {
            return FILLER.equals(upper(node.cobolName()));
        }

        private static String upper(String name) {
            return name.toUpperCase(Locale.ROOT);
        }

        private SourceLocation location(SpecNode node) {
            return node.location() != null ? node.location()
                    : new SourceLocation(spec.sourceName(), 1, 1);
        }

        private void error(SpecNode node, String message) {
            SourceLocation at = location(node);
            diagnostics.error(at.source(), at.line(), at.column(), node.path() + ": " + message);
        }
    }
}
