package pt.dazz.copymapper.copybook.spec;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.SourceVersion;
import pt.dazz.copymapper.annotation.Direction;
import pt.dazz.copymapper.cobol.Sign;
import pt.dazz.copymapper.codec.Formats;
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
import pt.dazz.copymapper.copybook.parse.DataParser;

/**
 * Translates a {@link MappingSpec} to the inputs of the copybook generator: a copybook with
 * one record, the mapping options, and the Java names.
 *
 * <p>Each decision of the spec that the generator cannot use gives an error diagnostic with
 * the path of the node and its location. The variant of a {@code REDEFINES} area decides the
 * area, so the direction of a node in the area is not used. A direction outside an area is
 * not used either: in Java, all items occupy their bytes.
 */
public final class SpecTranslator {

    private static final String FILLER = "FILLER";
    private static final String TWO_ALTERNATIVES =
            " The generator needs two different alternatives.";
    private static final Set<JsonType> DATE_TYPES =
            EnumSet.of(JsonType.DATE, JsonType.TIME, JsonType.DATE_TIME);
    private static final LocalDateTime SAMPLE = LocalDateTime.of(2026, 12, 31, 23, 59, 58);

    private SpecTranslator() {
    }

    /**
     * Translates a mapping spec. A decision that the generator cannot use gives an error
     * diagnostic. The name markers of the options are empty, so only the spec gives
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
        /** The COBOL name of the item that each node redefines. */
        private final Map<SpecNode, QualifiedName> redefined = new IdentityHashMap<>();
        /** The nodes with a REDEFINES text that names no earlier sibling. */
        private final Set<SpecNode> unresolved = Collections.newSetFromMap(
                new IdentityHashMap<>());
        /** The redefined node of each node that redefines a node of the spec. */
        private final Map<SpecNode, SpecNode> bases = new IdentityHashMap<>();
        /** The nodes of each REDEFINES area, the redefined node first, by redefined node. */
        private final Map<SpecNode, List<SpecNode>> areas = new LinkedHashMap<>();
        /** The node with the variant of each area, by redefined node. */
        private final Map<SpecNode, SpecNode> variants = new IdentityHashMap<>();
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
            check(spec.root(), null);
            // A copy of the record with the names only, to find the COBOL names.
            index = new ItemIndex(new Copybook(spec.sourceName(), List.of(shape(spec.root()))));
            redefines(spec.root());
            Copybook copybook = new Copybook(spec.sourceName(),
                    List.of(build(spec.root(), Usage.DISPLAY)));
            decide(spec.root(), false);
            undecidedAreas();
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

        /** Checks the names, the levels, and the OCCURS of the COBOL side. */
        private void check(SpecNode node, SpecNode parent) {
            dataName(node, "the COBOL name", node.cobolName());
            node.conditions().forEach(c -> dataName(node, "the condition name", c.name()));
            int level = node.level();
            if (parent == null) {
                if (level != 1) {
                    error(node, "the level of the record is " + level + ". A record needs the"
                            + " level 01.");
                }
            } else if (level < 2 || level > 49) {
                error(node, "the level " + level + " is not valid. An item in a group needs a"
                        + " level from 02 to 49.");
            } else if (level <= parent.level()) {
                error(node, "the level " + level + " is not more than the level "
                        + parent.level() + " of the group " + parent.path());
            }
            OccursSpec occurs = node.occurs();
            if (occurs != null && (occurs.max() < 1 || occurs.min() < 0
                    || occurs.min() > occurs.max())) {
                error(node, "OCCURS " + occurs.min() + " TO " + occurs.max() + " is not valid."
                        + " The maximum must be 1 or more, and the minimum must be from 0 to"
                        + " the maximum.");
            }
            node.children().forEach(child -> check(child, node));
        }

        private void dataName(SpecNode node, String what, String name) {
            if (!DataParser.isDataName(name)) {
                error(node, what + " '" + name + "' is not a data name. Write one COBOL word"
                        + " that is not a clause word.");
            }
        }

        private DataItem shape(SpecNode node) {
            List<DataItem> children = node.children().stream().map(this::shape).toList();
            return new DataItem(node.level(), upper(node.cobolName()), null, null, null, null,
                    List.of(), children, spec.sourceName(), 1, 1, null, null, null);
        }

        // ---- REDEFINES ----

        /** Resolves the REDEFINES of the children of each group, and finds the areas. */
        private void redefines(SpecNode node) {
            List<SpecNode> children = node.children();
            for (int i = 0; i < children.size(); i++) {
                SpecNode child = children.get(i);
                if (child.redefines() != null) {
                    redefined.put(child, redefinedName(child, children.subList(0, i)));
                }
                redefines(child);
            }
        }

        /**
         * Gives the name of the redefined item. A spec path must be an earlier sibling without
         * a number. Other text is a COBOL name, as the draft writes it when it cannot resolve
         * the name. That name must be the name of an earlier sibling.
         */
        private QualifiedName redefinedName(SpecNode node, List<SpecNode> earlier) {
            String text = node.redefines();
            SpecNode target = byPath.get(text);
            if (target == null) {
                if (earlier.stream().noneMatch(s -> upper(s.cobolName()).equals(upper(text)))) {
                    error(node, "the REDEFINES item '" + text + "' is not a path of the spec"
                            + " and not the name of an earlier sibling");
                    unresolved.add(node);
                }
                return new QualifiedName(List.of(upper(text)));
            }
            if (lastSegment(text).contains("#")) {
                error(node, "the REDEFINES item " + text + " is a duplicate sibling name."
                        + " COBOL cannot name it.");
            } else if (earlier.stream().noneMatch(sibling -> sibling == target)) {
                error(node, "the REDEFINES item " + text + " is not an earlier sibling");
            } else {
                bases.put(node, target);
                areas.computeIfAbsent(target, base -> new ArrayList<>(List.of(base))).add(node);
            }
            return new QualifiedName(List.of(upper(target.cobolName())));
        }

        /** Reports each group area that the mapper must decide and that has no variant. */
        private void undecidedAreas() {
            areas.forEach((base, area) -> {
                boolean groups = area.stream().allMatch(SpecNode::isGroup);
                if (groups && !variants.containsKey(base) && !nested(base)) {
                    error(base, "the REDEFINES area has no variant. Set a discriminator or a"
                            + " fixed selection.");
                }
            });
        }

        /** Tells if a node is inside an alternative of another area: the mapper keeps it. */
        private boolean nested(SpecNode node) {
            for (SpecNode a = parents.get(node); a != null; a = parents.get(a)) {
                if (bases.containsKey(a) || areas.containsKey(a)) {
                    return true;
                }
            }
            return false;
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
                    node.usage(), occurs(node), redefined.get(node), conditions,
                    children, at.source(), at.line(), at.column(), node.declaration(),
                    node.value() == null ? null : literal(node.value()),
                    sign(node, picture, usage));
            items.put(node, item);
            return item;
        }

        private Picture picture(SpecNode node) {
            if (node.picture() == null) {
                if (!node.isGroup()) {
                    error(node, "an elementary item needs a picture");
                }
                return null;
            }
            if (node.isGroup()) {
                error(node, "a group must not have a picture");
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

        /**
         * Gives the sign of the AST. The draft writes TRAILING for the default sign, and the
         * AST has null for it. A sign applies only to a signed DISPLAY picture without edit.
         */
        private Sign sign(SpecNode node, Picture picture, Usage usage) {
            Sign sign = node.sign();
            if (sign == null || node.picture() != null && picture == null) {
                // No sign, or the picture has an error.
                return sign;
            }
            boolean applies = picture != null && picture.edited() == null && picture.signed()
                    && usage == Usage.DISPLAY;
            if (!applies) {
                error(node, "the sign " + sign + " needs a signed DISPLAY picture without edit"
                        + " symbols");
                return sign;
            }
            return sign == Sign.TRAILING ? null : sign;
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

        /**
         * Names the counter with a COBOL name, or keeps the text that is not a path. That text
         * must name one item of the record.
         */
        private QualifiedName dependingOn(SpecNode node, String text) {
            SpecNode target = byPath.get(text);
            if (target != null) {
                String name = nameOf(node, target);
                return name != null ? new QualifiedName(List.of(name.split(" OF ")))
                        : new QualifiedName(List.of(upper(target.cobolName())));
            }
            QualifiedName name;
            try {
                name = QualifiedName.parse(text);
            } catch (IllegalArgumentException e) {
                name = new QualifiedName(List.of(text));
            }
            ItemIndex.Lookup lookup = index.find(name);
            if (lookup instanceof ItemIndex.Ambiguous a) {
                error(node, "the DEPENDING ON item '" + text + "' names more than one item of"
                        + " the record: " + String.join(", ", a.matches()));
            } else if (lookup instanceof ItemIndex.NotFound) {
                error(node, "the DEPENDING ON item '" + text + "' is not a path of the spec"
                        + " and not a name in the record");
            }
            return name;
        }

        private static Condition condition(ConditionSpec spec, SourceLocation at) {
            List<ConditionValue> values = spec.values().stream()
                    .map(r -> new ConditionValue(literal(r.from()),
                            r.thru() == null ? null : literal(r.thru())))
                    .toList();
            return new Condition(upper(spec.name()), values, at.line(), at.column());
        }

        private static Literal literal(ValueSpec value) {
            return new Literal(Literal.Kind.valueOf(value.kind()), value.text());
        }

        // ---- the REST side ----

        private void decide(SpecNode node, boolean inFiller) {
            javaName(node, inFiller);
            date(node);
            counter(node);
            if (node.table() != null) {
                table(node, node.table());
            }
            if (node.variant() != null) {
                variant(node, node.variant());
            }
            boolean filler = inFiller || isFiller(node);
            node.children().forEach(child -> decide(child, filler));
        }

        private void date(SpecNode node) {
            boolean dateType = DATE_TYPES.contains(node.jsonType());
            if (node.format() == null) {
                if (dateType) {
                    error(node, "the JSON type " + node.jsonType() + " needs a format");
                }
                return;
            }
            if (!dateType) {
                error(node, "a format needs the JSON type DATE, TIME, or DATE_TIME. The"
                        + " generator uses a format only for a date or a time.");
                return;
            }
            JsonType kind = kindOf(node.format());
            if (kind != null && kind != node.jsonType()) {
                error(node, "the format " + node.format() + " is a " + kind + " pattern, but"
                        + " the JSON type is " + node.jsonType());
                return;
            }
            String name = nameOf(node, node);
            if (name != null) {
                dates.put(name, node.format());
            }
        }

        /**
         * Gives the kind of a date pattern, with the rule of the generator: the first of
         * LocalDateTime, LocalDate, and LocalTime that the pattern can write and read. Gives
         * null for a pattern that the generator rejects; the generator reports it.
         */
        private static JsonType kindOf(String pattern) {
            DateTimeFormatter formatter;
            try {
                formatter = Formats.of(pattern);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (fits(formatter, SAMPLE, LocalDateTime::from)) {
                return JsonType.DATE_TIME;
            }
            if (fits(formatter, SAMPLE.toLocalDate(), LocalDate::from)) {
                return JsonType.DATE;
            }
            return fits(formatter, SAMPLE.toLocalTime(), LocalTime::from) ? JsonType.TIME
                    : null;
        }

        private static boolean fits(DateTimeFormatter formatter, TemporalAccessor value,
                TemporalQuery<?> query) {
            try {
                formatter.parse(formatter.format(value), query);
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        }

        /** The generator uses the DEPENDING ON item as the counter. */
        private void counter(SpecNode node) {
            if (node.counter() == null) {
                return;
            }
            String dependingOn = node.occurs() == null ? null : node.occurs().dependingOn();
            if (dependingOn == null) {
                error(node, "the counter " + node.counter() + " needs OCCURS DEPENDING ON."
                        + " The generator uses the DEPENDING ON item as the counter.");
            } else if (!Objects.equals(node.counter(), dependingOn)) {
                error(node, "the counter " + node.counter() + " is not the DEPENDING ON item "
                        + dependingOn + ". The generator uses the DEPENDING ON item as the"
                        + " counter.");
            }
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

        // ---- variants ----

        /** Finds the area of a variant, and checks that the area has one variant only. */
        private void variant(SpecNode node, VariantSpec variant) {
            if (unresolved.contains(node)) {
                // The REDEFINES text has an error.
                return;
            }
            SpecNode base = node.redefines() == null ? node : bases.get(node);
            List<SpecNode> area = base == null ? null : areas.get(base);
            if (area == null) {
                error(node, "the variant is not on a REDEFINES area");
                return;
            }
            SpecNode first = variants.putIfAbsent(base, node);
            if (first != null) {
                error(node, "the REDEFINES area of " + base.path() + " already has a variant"
                        + " on " + first.path() + ". Set one variant for each area.");
            } else if (variant instanceof VariantSpec.Discriminator d) {
                discriminator(node, d, area);
            } else {
                fixedSelection(node, (VariantSpec.FixedSelection) variant, area);
            }
        }

        /** Gives the alternative of a variant, or null after an error. */
        private SpecNode alternative(SpecNode node, String path, List<SpecNode> area) {
            SpecNode alternative = ref(node, path);
            if (alternative != null && !area.contains(alternative)) {
                error(node, "the alternative " + path + " is not in the REDEFINES area of "
                        + area.get(0).path());
                return null;
            }
            return alternative;
        }

        private void discriminator(SpecNode node, VariantSpec.Discriminator variant,
                List<SpecNode> area) {
            if (variant.values().isEmpty()) {
                error(node, "the discriminator has no values");
                return;
            }
            SpecNode field = ref(node, variant.path());
            boolean valid = field != null;
            Map<SpecNode, String> byAlternative = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : variant.values().entrySet()) {
                SpecNode alternative = alternative(node, entry.getValue(), area);
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

        private void fixedSelection(SpecNode node, VariantSpec.FixedSelection variant,
                List<SpecNode> area) {
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
            if (area.size() > 2) {
                error(node, "the REDEFINES area has " + area.size() + " alternatives. A fixed"
                        + " selection needs an area of two alternatives. Set a discriminator.");
                return;
            }
            SpecNode request = alternative(node, variant.request(), area);
            SpecNode response = alternative(node, variant.response(), area);
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

        // ---- Java names ----

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
                error(node, prefix + " is in a FILLER group. The generator puts the items of"
                        + " a FILLER group in the enclosing class, and a FILLER has no stable"
                        + " path for a decision.");
            } else if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                error(node, prefix + " is not a Java identifier");
            } else if (lastSegment(node.path()).contains("#")) {
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
         * qualifier. The target matches each of these names, so a unique match is the
         * target. A FILLER target is not in the index, and a duplicate sibling name stays
         * ambiguous.
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

        private static String lastSegment(String path) {
            return path.substring(path.lastIndexOf('.') + 1);
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
