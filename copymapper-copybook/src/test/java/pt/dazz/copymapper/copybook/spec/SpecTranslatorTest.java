package pt.dazz.copymapper.copybook.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pt.dazz.copymapper.annotation.Direction;
import pt.dazz.copymapper.cobol.Sign;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.ast.DataItem;
import pt.dazz.copymapper.copybook.ast.QualifiedName;
import pt.dazz.copymapper.copybook.ast.Usage;
import pt.dazz.copymapper.copybook.diag.Diagnostic;
import pt.dazz.copymapper.copybook.diag.Severity;
import pt.dazz.copymapper.copybook.map.ModelMapper;
import pt.dazz.copymapper.copybook.map.SubTypeDecision;
import pt.dazz.copymapper.copybook.parse.CopybookParser;
import pt.dazz.copymapper.copybook.parse.ParseOptions;
import pt.dazz.copymapper.copybook.parse.SourceFormat;

class SpecTranslatorTest {

    // ---- helpers ----

    private static Copybook parse(String... lines) {
        var result = CopybookParser.parse("ORDER.cpy", String.join("\n", lines),
                new ParseOptions(SourceFormat.FREE, StandardCharsets.UTF_8, List.of(), null));
        assertThat(result.copybook()).as(result.diagnostics().toString()).isNotNull();
        return result.copybook();
    }

    private static MappingSpec draft(String... lines) {
        Copybook copybook = parse(lines);
        return MappingSpecs.draft(copybook, copybook.records().get(0).name(),
                DraftOptions.defaults());
    }

    /** A mutable copy of a node, to change some components. */
    private static final class Edit {
        String cobolName;
        int level;
        String picture;
        Usage usage;
        Sign sign;
        OccursSpec occurs;
        String redefines;
        SourceLocation location;
        boolean blankWhenZero;
        JsonType jsonType;
        String format;
        TableSpec table;
        VariantSpec variant;
        String javaName;
        FieldDirection direction;
        String jsonName;
        String counter;
        List<ConditionSpec> conditions;
        private final SpecNode node;

        Edit(SpecNode node) {
            this.node = node;
            cobolName = node.cobolName();
            level = node.level();
            picture = node.picture();
            usage = node.usage();
            sign = node.sign();
            occurs = node.occurs();
            redefines = node.redefines();
            location = node.location();
            blankWhenZero = node.blankWhenZero();
            jsonType = node.jsonType();
            format = node.format();
            table = node.table();
            variant = node.variant();
            javaName = node.javaName();
            direction = node.direction();
            jsonName = node.jsonName();
            counter = node.counter();
            conditions = node.conditions();
        }

        SpecNode build(List<SpecNode> children) {
            return new SpecNode(cobolName, level, node.path(), picture, usage, sign,
                    occurs, redefines, node.value(), conditions, location,
                    node.declaration(), blankWhenZero, node.edited(), jsonName, direction,
                    jsonType, format, table, counter, variant, javaName, children);
        }
    }

    private static SpecNode edit(SpecNode node, String path, java.util.function.Consumer<Edit> change) {
        List<SpecNode> children = new ArrayList<>();
        for (SpecNode child : node.children()) {
            children.add(edit(child, path, change));
        }
        Edit e = new Edit(node);
        if (node.path().equals(path)) {
            change.accept(e);
        }
        return e.build(children);
    }

    private static MappingSpec with(MappingSpec spec, String path,
            java.util.function.Consumer<Edit> change) {
        return new MappingSpec(spec.formatVersion(), spec.sourceName(), spec.sourceHash(),
                spec.charset(), spec.byteOrder(), edit(spec.root(), path, change));
    }

    private static DataItem item(Copybook copybook, String path) {
        String[] parts = path.split("\\.");
        DataItem item = copybook.records().get(0);
        assertThat(item.name()).isEqualTo(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String name = parts[i];
            item = item.children().stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElseThrow();
        }
        return item;
    }

    private static List<String> errors(Translation translation) {
        return translation.diagnostics().stream()
                .filter(d -> d.severity() == Severity.ERROR).map(Diagnostic::format).toList();
    }

    private static Translation clean(MappingSpec spec) {
        Translation translation = SpecTranslator.translate(spec);
        assertThat(translation.diagnostics()).isEmpty();
        assertThat(translation.hasErrors()).isFalse();
        return translation;
    }

    // ---- the COBOL side ----

    @Test
    void rebuildsTheAstOfADraft() {
        Copybook parsed = parse(
                "01 ORDER-REC.",
                "   05 ORDER-ID PIC X(10) VALUE 'NONE'.",
                "   05 STATUS-CODE PIC X.",
                "      88 IS-OPEN VALUE 'O'.",
                "      88 IS-RANGE VALUES 'A' THRU 'F', 'X'.",
                "   05 AMOUNTS COMP-3.",
                "      10 NET PIC S9(7)V99.",
                "      10 TAX PIC S9(5)V99 VALUE ZERO.",
                "   05 SIGNED-DISPLAY PIC S9(4).",
                "   05 LEADING-SEP PIC S9(4) SIGN LEADING SEPARATE.",
                "   05 EDITED PIC ZZ9.99 BLANK WHEN ZERO.",
                "   05 BLANK-NUM PIC 9(3) BLANK WHEN ZERO.",
                "   05 FILLER PIC X(2).",
                "   05 LINE-COUNT PIC 9(2) COMP.",
                "   05 LINES OCCURS 1 TO 5 DEPENDING ON LINE-COUNT.",
                "      10 SKU PIC X(8).",
                "   05 FIXED-ROWS OCCURS 3.",
                "      10 CODE PIC X(2).",
                "   05 PARTY.",
                "      10 PARTY-NAME PIC X(10).",
                "   05 PARTY-ALT REDEFINES PARTY.",
                "      10 ALT-NAME PIC X(10).",
                "   05 COUNTER PIC 9(4) COMP-5.");
        MappingSpec spec = with(MappingSpecs.draft(parsed, "ORDER-REC", DraftOptions.defaults()),
                "ORDER-REC.PARTY", e -> e.variant = new VariantSpec.FixedSelection(
                        "ORDER-REC.PARTY", "ORDER-REC.PARTY-ALT"));

        Translation translation = clean(spec);

        assertThat(translation.copybook().sourceName()).isEqualTo("ORDER.cpy");
        assertThat(translation.copybook().records()).hasSize(1);
        assertSame(parsed.records().get(0), translation.copybook().records().get(0));
    }

    private static void assertSame(DataItem expected, DataItem actual) {
        String at = expected.name() + " at " + expected.line();
        assertThat(actual.level()).as(at).isEqualTo(expected.level());
        assertThat(actual.name()).as(at).isEqualTo(expected.name());
        assertThat(actual.picture()).as(at).isEqualTo(expected.picture());
        assertThat(actual.occurs()).as(at).isEqualTo(expected.occurs());
        assertThat(actual.redefines()).as(at).isEqualTo(expected.redefines());
        assertThat(actual.source()).as(at).isEqualTo(expected.source());
        assertThat(actual.line()).as(at).isEqualTo(expected.line());
        assertThat(actual.column()).as(at).isEqualTo(expected.column());
        assertThat(actual.declaration()).as(at).isEqualTo(expected.declaration());
        assertThat(actual.value()).as(at).isEqualTo(expected.value());
        assertThat(actual.sign()).as(at).isEqualTo(expected.sign());
        if (expected.usage() != null) {
            assertThat(actual.usage()).as(at).isEqualTo(expected.usage());
        }
        // The spec does not keep the place of a level 88 item.
        assertThat(actual.conditions()).as(at).hasSameSizeAs(expected.conditions());
        for (int i = 0; i < expected.conditions().size(); i++) {
            assertThat(actual.conditions().get(i).name())
                    .isEqualTo(expected.conditions().get(i).name());
            assertThat(actual.conditions().get(i).values())
                    .isEqualTo(expected.conditions().get(i).values());
        }
        assertThat(actual.children()).as(at).hasSameSizeAs(expected.children());
        for (int i = 0; i < expected.children().size(); i++) {
            assertSame(expected.children().get(i), actual.children().get(i));
        }
    }

    @Test
    void givesTheLocationOfTheNodeToEachCondition() {
        var spec = draft("01 R.", "   05 S PIC X.", "      88 OK VALUE 'Y'.");
        var condition = item(clean(spec).copybook(), "R.S").conditions().get(0);
        assertThat(condition.line()).isEqualTo(2);
        assertThat(condition.column()).isEqualTo(4);
    }

    @Test
    void aSignWhereNoSignAppliesIsAnError() {
        var spec = draft("01 R.", "   05 A PIC S9(3).", "   05 B PIC S9(3) COMP-3.",
                "   05 C PIC 9(3).", "   05 D PIC -99.", "   05 G.", "      10 X PIC X.",
                "   05 L PIC S9(3) SIGN LEADING.");
        spec = with(spec, "R.B", e -> e.sign = Sign.TRAILING);
        spec = with(spec, "R.C", e -> e.sign = Sign.LEADING);
        spec = with(spec, "R.D", e -> e.sign = Sign.TRAILING);
        spec = with(spec, "R.G", e -> e.sign = Sign.TRAILING);
        Translation translation = SpecTranslator.translate(spec);
        String needs = " needs a signed DISPLAY picture without edit symbols";
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:3:4: error: R.B: the sign TRAILING" + needs,
                "ORDER.cpy:4:4: error: R.C: the sign LEADING" + needs,
                "ORDER.cpy:5:4: error: R.D: the sign TRAILING" + needs,
                "ORDER.cpy:6:4: error: R.G: the sign TRAILING" + needs);
        Copybook copybook = translation.copybook();
        assertThat(item(copybook, "R.A").sign()).isNull();
        assertThat(item(copybook, "R.L").sign()).isEqualTo(Sign.LEADING);
        assertThat(item(copybook, "R.B").sign()).isEqualTo(Sign.TRAILING);
    }

    @Test
    void anElementaryItemWithoutAPictureIsAnError() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> e.picture = null);
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:2:4: error: R.A: an elementary item needs a picture");
    }

    @Test
    void aGroupWithAPictureIsAnError() {
        var spec = with(draft("01 R.", "   05 G.", "      10 A PIC X."), "R.G",
                e -> e.picture = "X(1)");
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:2:4: error: R.G: a group must not have a picture");
        assertThat(item(translation.copybook(), "R.G").picture()).isNull();
    }

    @Test
    void upperCasesTheConditionNames() {
        var spec = with(draft("01 R.", "   05 S PIC X.", "      88 OK VALUE 'Y'."), "R.S",
                e -> e.conditions = List.of(new ConditionSpec("ok", e.conditions.get(0).values())));
        assertThat(item(clean(spec).copybook(), "R.S").conditions().get(0).name())
                .isEqualTo("OK");
    }

    @Test
    void keepsTheUsageOfEachNode() {
        var spec = draft("01 R.", "   05 G COMP-3.", "      10 N PIC S9(5).");
        Copybook copybook = clean(spec).copybook();
        assertThat(item(copybook, "R.G").usage()).isEqualTo(Usage.COMP3);
        assertThat(item(copybook, "R.G.N").usage()).isEqualTo(Usage.COMP3);
        assertThat(item(copybook, "R").usage()).isEqualTo(Usage.DISPLAY);
    }

    @Test
    void upperCasesTheCobolNames() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> e.cobolName = "a");
        assertThat(item(clean(spec).copybook(), "R.A").name()).isEqualTo("A");
    }

    @Test
    void usesTheSourceNameWithoutALocation() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> e.location = null);
        DataItem a = item(clean(spec).copybook(), "R.A");
        assertThat(a.source()).isEqualTo("ORDER.cpy");
        assertThat(a.line()).isEqualTo(1);
        assertThat(a.column()).isEqualTo(1);
    }

    @Test
    void anInvalidPictureIsAnError() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> {
            e.picture = "X(0)";
            e.sign = Sign.TRAILING;
        });
        Translation translation = SpecTranslator.translate(spec);
        assertThat(translation.hasErrors()).isTrue();
        assertThat(errors(translation)).singleElement().asString()
                .startsWith("ORDER.cpy:2:4: error: R.A: the picture X(0) is not valid");
        assertThat(item(translation.copybook(), "R.A").picture()).isNull();
        assertThat(item(translation.copybook(), "R.A").sign()).isEqualTo(Sign.TRAILING);
    }

    @Test
    void blankWhenZeroOnTextIsAnError() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> e.blankWhenZero = true);
        assertThat(errors(SpecTranslator.translate(spec))).singleElement().asString()
                .contains("R.A: the picture X is not valid")
                .contains("BLANK WHEN ZERO needs a numeric picture");
    }

    @Test
    void namesTheDependingOnItemWithTheShortestQualification() {
        var spec = draft("01 R.", "   05 G1.", "      10 CNT PIC 9(2).",
                "      10 T1 OCCURS 1 TO 3 DEPENDING ON CNT OF G1.", "         15 X PIC X.",
                "   05 G2.", "      10 CNT PIC 9(2).");
        DataItem t1 = item(clean(spec).copybook(), "R.G1.T1");
        assertThat(t1.occurs().dependingOn()).isEqualTo(QualifiedName.parse("CNT OF G1"));
    }

    @Test
    void aDependingOnTextThatNamesAnItemOfTheRecordStaysAsWritten() {
        var spec = with(draft("01 R.", "   05 G.", "      10 N PIC 9.",
                "   05 T OCCURS 1 TO 3 DEPENDING ON N.", "      10 X PIC X."), "R.T", e -> {
                    e.occurs = new OccursSpec(1, 3, "n of g");
                    e.counter = "n of g";
                });
        DataItem t = item(clean(spec).copybook(), "R.T");
        assertThat(t.occurs().dependingOn()).isEqualTo(QualifiedName.parse("N OF G"));
    }

    @Test
    void aDependingOnTextThatNamesNoItemOfTheRecordIsAnError() {
        var spec = with(draft("01 R.", "   05 N PIC 9.", "   05 T OCCURS 1 TO 3 DEPENDING ON N.",
                "      10 X PIC X."), "R.T", e -> {
                    e.occurs = new OccursSpec(1, 3, "m of other");
                    e.counter = "m of other";
                });
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly("ORDER.cpy:3:4:"
                + " error: R.T: the DEPENDING ON item 'm of other' is not a path of the spec"
                + " and not a name in the record");
    }

    @Test
    void aDependingOnTextThatNamesTwoItemsIsAnError() {
        var spec = with(draft("01 R.", "   05 G1.", "      10 N PIC 9.", "   05 G2.",
                "      10 N PIC 9.", "   05 T OCCURS 1 TO 3 DEPENDING ON N OF G1.",
                "      10 X PIC X."), "R.T", e -> {
                    e.occurs = new OccursSpec(1, 3, "N");
                    e.counter = "N";
                });
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly("ORDER.cpy:6:4:"
                + " error: R.T: the DEPENDING ON item 'N' names more than one item of the"
                + " record: N OF G1 OF R, N OF G2 OF R");
    }

    // ---- the COBOL names, levels, and OCCURS ----

    private static final String NAMES_ERROR = " is not a data name. Write one COBOL word that"
            + " is not a clause word.";

    @Test
    void aNameThatIsNotADataNameIsAnError() {
        var spec = draft("01 R.", "   05 A PIC X.", "      88 OK VALUE 'Y'.", "   05 B PIC X.");
        spec = with(spec, "R.A", e -> {
            e.cobolName = "KIND X";
            e.conditions = List.of(new ConditionSpec("K A", e.conditions.get(0).values()));
        });
        spec = with(spec, "R.B", e -> e.cobolName = "");
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:2:4: error: R.A: the COBOL name 'KIND X'" + NAMES_ERROR,
                "ORDER.cpy:2:4: error: R.A: the condition name 'K A'" + NAMES_ERROR,
                "ORDER.cpy:4:4: error: R.B: the COBOL name ''" + NAMES_ERROR);
    }

    @Test
    void aCounterWithANameThatIsNotADataNameGivesOneError() {
        var spec = with(draft("01 R.", "   05 N PIC 9.", "   05 T OCCURS 1 TO 3 DEPENDING ON N.",
                "      10 X PIC X."), "R.N", e -> e.cobolName = "N X");
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:2:4: error: R.N: the COBOL name 'N X'" + NAMES_ERROR);
    }

    private static final String[] LEVELS = {"01 R.", "   05 G.", "      10 A PIC X.",
        "   05 B PIC X."};

    private static List<String> levelErrors(String path, int level) {
        return errors(SpecTranslator.translate(with(draft(LEVELS), path, e -> e.level = level)));
    }

    @Test
    void theRecordNeedsTheLevel01() {
        assertThat(levelErrors("R", 2)).containsExactly("ORDER.cpy:1:1: error: R: the level of"
                + " the record is 2. A record needs the level 01.");
    }

    @Test
    void anItemInAGroupNeedsALevelFrom02To49() {
        String needs = " is not valid. An item in a group needs a level from 02 to 49.";
        assertThat(levelErrors("R.B", 1)).containsExactly(
                "ORDER.cpy:4:4: error: R.B: the level 1" + needs);
        assertThat(levelErrors("R.B", 50)).containsExactly(
                "ORDER.cpy:4:4: error: R.B: the level 50" + needs);
        assertThat(levelErrors("R.B", 2)).isEmpty();
        assertThat(levelErrors("R.B", 49)).isEmpty();
    }

    @Test
    void anItemNeedsALevelMoreThanTheLevelOfItsGroup() {
        assertThat(levelErrors("R.G.A", 5)).containsExactly("ORDER.cpy:3:7: error: R.G.A: the"
                + " level 5 is not more than the level 5 of the group R.G");
        assertThat(levelErrors("R.G.A", 4)).containsExactly("ORDER.cpy:3:7: error: R.G.A: the"
                + " level 4 is not more than the level 5 of the group R.G");
        assertThat(levelErrors("R.G.A", 6)).isEmpty();
    }

    private static List<String> occursErrors(int min, int max) {
        return errors(SpecTranslator.translate(with(draft("01 R.", "   05 T OCCURS 3.",
                "      10 X PIC X."), "R.T", e -> e.occurs = new OccursSpec(min, max, null))));
    }

    @Test
    void anOccursNeedsAMaximumOf1OrMoreAndAMinimumFrom0ToTheMaximum() {
        String needs = " is not valid. The maximum must be 1 or more, and the minimum must be"
                + " from 0 to the maximum.";
        assertThat(occursErrors(5, 3)).containsExactly(
                "ORDER.cpy:2:4: error: R.T: OCCURS 5 TO 3" + needs);
        assertThat(occursErrors(0, 0)).containsExactly(
                "ORDER.cpy:2:4: error: R.T: OCCURS 0 TO 0" + needs);
        assertThat(occursErrors(-1, 3)).containsExactly(
                "ORDER.cpy:2:4: error: R.T: OCCURS -1 TO 3" + needs);
        assertThat(occursErrors(0, 1)).isEmpty();
        assertThat(occursErrors(1, 1)).isEmpty();
        assertThat(occursErrors(3, 3)).isEmpty();
    }

    @Test
    void anInvalidDependingOnTextIsAnError() {
        var spec = with(draft("01 R.", "   05 N PIC 9.", "   05 T OCCURS 1 TO 3 DEPENDING ON N.",
                "      10 X PIC X."), "R.T", e -> {
                    e.occurs = new OccursSpec(1, 3, " ");
                    e.counter = null;
                });
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).singleElement().asString()
                .isEqualTo("ORDER.cpy:3:4: error: R.T: the DEPENDING ON item ' ' is not a path"
                        + " of the spec and not a name in the record");
        assertThat(item(translation.copybook(), "R.T").occurs().dependingOn())
                .isEqualTo(new QualifiedName(List.of(" ")));
    }

    @Test
    void aDependingOnItemWithoutAUniqueNameIsAnError() {
        var spec = with(draft("01 R.", "   05 N PIC 9.", "   05 N PIC 9.",
                "   05 T OCCURS 1 TO 3 DEPENDING ON N OF R.", "      10 X PIC X."), "R.T",
                e -> {
                    e.occurs = new OccursSpec(1, 3, "R.N#2");
                    e.counter = "R.N#2";
                });
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).singleElement().asString()
                .isEqualTo("ORDER.cpy:4:4: error: R.T: no COBOL name designates only the item"
                        + " R.N#2. A FILLER or a duplicate sibling name cannot be named.");
        assertThat(item(translation.copybook(), "R.T").occurs().dependingOn())
                .isEqualTo(QualifiedName.parse("N"));
    }

    @Test
    void namesTheRedefinedItem() {
        var spec = draft("01 R.", "   05 G.", "      10 A PIC X(2).",
                "      10 B REDEFINES A PIC 99.");
        assertThat(item(clean(spec).copybook(), "R.G.B").redefines())
                .isEqualTo(QualifiedName.parse("A"));
        var text = with(spec, "R.G.B", e -> e.redefines = "a");
        assertThat(item(clean(text).copybook(), "R.G.B").redefines())
                .isEqualTo(QualifiedName.parse("A"));
    }

    @Test
    void aRedefinesTextThatIsNotAnEarlierSiblingIsAnError() {
        var spec = draft("01 R.", "   05 G.", "      10 A PIC X(2).",
                "      10 B REDEFINES A PIC 99.", "      10 C PIC X(2).");
        for (String text : List.of("BOGUS", "c", " ")) {
            var bogus = with(spec, "R.G.B", e -> e.redefines = text);
            assertThat(errors(SpecTranslator.translate(bogus))).as(text).containsExactly(
                    "ORDER.cpy:4:7: error: R.G.B: the REDEFINES item '" + text + "' is not a"
                            + " path of the spec and not the name of an earlier sibling");
        }
    }

    @Test
    void aVariantOnAnAreaWithAnUnknownRedefinesTextGivesOneError() {
        var spec = with(draft(AREA), "R.B", e -> {
            e.redefines = "BOGUS";
            e.variant = new VariantSpec.FixedSelection("R.A", "R.B");
        });
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly("ORDER.cpy:5:4:"
                + " error: R.B: the REDEFINES item 'BOGUS' is not a path of the spec and not"
                + " the name of an earlier sibling");
    }

    @Test
    void aRedefinesPathThatIsNotAnEarlierSiblingIsAnError() {
        var spec = draft("01 R.", "   05 Z PIC X(2).", "   05 G.", "      10 A PIC X(2).",
                "      10 A PIC X(2).", "      10 B REDEFINES A PIC 99.", "      10 C PIC X(2).");
        var number = with(spec, "R.G.B", e -> e.redefines = "R.G.A#2");
        Translation one = SpecTranslator.translate(number);
        assertThat(errors(one)).containsExactly("ORDER.cpy:6:7: error: R.G.B: the REDEFINES"
                + " item R.G.A#2 is a duplicate sibling name. COBOL cannot name it.");
        assertThat(item(one.copybook(), "R.G.B").redefines()).isEqualTo(QualifiedName.parse("A"));
        var later = with(spec, "R.G.B", e -> e.redefines = "R.G.C");
        assertThat(errors(SpecTranslator.translate(later))).containsExactly("ORDER.cpy:6:7:"
                + " error: R.G.B: the REDEFINES item R.G.C is not an earlier sibling");
        var outside = with(spec, "R.G.B", e -> e.redefines = "R.Z");
        assertThat(errors(SpecTranslator.translate(outside))).containsExactly("ORDER.cpy:6:7:"
                + " error: R.G.B: the REDEFINES item R.Z is not an earlier sibling");
    }

    @Test
    void aCounterThatIsNotTheDependingOnItemIsAnError() {
        var spec = draft("01 R.", "   05 N PIC 9.", "   05 M PIC 9.",
                "   05 T OCCURS 1 TO 3 DEPENDING ON N.", "      10 X PIC X.",
                "   05 F OCCURS 3.", "      10 Y PIC X.");
        assertThat(item(clean(spec).copybook(), "R.T").occurs().dependingOn())
                .isEqualTo(QualifiedName.parse("N"));
        spec = with(spec, "R.T", e -> e.counter = "R.M");
        spec = with(spec, "R.F", e -> e.counter = "R.M");
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:4:4: error: R.T: the counter R.M is not the DEPENDING ON item R.N."
                        + " The generator uses the DEPENDING ON item as the counter.",
                "ORDER.cpy:6:4: error: R.F: the counter R.M needs OCCURS DEPENDING ON. The"
                        + " generator uses the DEPENDING ON item as the counter.");
        var plain = with(draft("01 R.", "   05 N PIC 9."), "R.N", e -> e.counter = "R.N");
        assertThat(errors(SpecTranslator.translate(plain))).containsExactly(
                "ORDER.cpy:2:4: error: R.N: the counter R.N needs OCCURS DEPENDING ON. The"
                        + " generator uses the DEPENDING ON item as the counter.");
    }

    // ---- the REST side ----

    @Test
    void usesNoNameMarkersAndNoUndecidedOption() {
        var options = clean(draft("01 R.", "   05 A PIC X.")).options();
        assertThat(options.stripPrefixes()).isEmpty();
        assertThat(options.inputMarkers()).isEmpty();
        assertThat(options.outputMarkers()).isEmpty();
        assertThat(options.directions()).isEmpty();
        assertThat(options.subTypes()).isEmpty();
        assertThat(options.countFields()).isEmpty();
        assertThat(options.maxRecordLength()).isNull();
        assertThat(options.dates()).isEmpty();
        assertThat(options.continuable()).isEmpty();
        assertThat(options.validatable()).isEmpty();
        assertThat(options.conditions()).isEmpty();
        assertThat(options.emptyItems()).isEmpty();
        assertThat(options.emptyKeys()).isEmpty();
        assertThat(options.partialTables()).isEmpty();
        assertThat(options.views()).isEmpty();
    }

    @Test
    void givesADateHintForEachDateFormat() {
        var spec = draft("01 R.", "   05 D PIC 9(8).", "   05 T PIC 9(6).", "   05 S PIC X(14).",
                "   05 F PIC X(8).", "   05 N PIC 9(8).");
        spec = with(spec, "R.D", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyyMMdd";
        });
        spec = with(spec, "R.T", e -> {
            e.jsonType = JsonType.TIME;
            e.format = "HHmmss";
        });
        spec = with(spec, "R.S", e -> {
            e.jsonType = JsonType.DATE_TIME;
            e.format = "yyyyMMddHHmmss";
        });
        assertThat(clean(spec).options().dates()).containsExactly(Map.entry("D", "yyyyMMdd"),
                Map.entry("T", "HHmmss"), Map.entry("S", "yyyyMMddHHmmss"));
    }

    @Test
    void aFormatWithoutADateTypeIsAnError() {
        var spec = draft("01 R.", "   05 F PIC 9(8).", "   05 G PIC 9(8).");
        spec = with(spec, "R.F", e -> e.format = "#,##0");
        spec = with(spec, "R.G", e -> {
            e.format = "yyyyMMdd";
            e.jsonType = JsonType.DECIMAL_STRING;
        });
        Translation translation = SpecTranslator.translate(spec);
        String text = ": a format needs the JSON type DATE, TIME, or DATE_TIME. The generator"
                + " uses a format only for a date or a time.";
        assertThat(errors(translation)).containsExactly("ORDER.cpy:2:4: error: R.F" + text,
                "ORDER.cpy:3:4: error: R.G" + text);
        assertThat(translation.options().dates()).isEmpty();
    }

    @Test
    void aDateTypeWithoutAFormatIsAnError() {
        var spec = draft("01 R.", "   05 D PIC 9(8).", "   05 T PIC 9(6).", "   05 S PIC 9(14).");
        spec = with(spec, "R.D", e -> e.jsonType = JsonType.DATE);
        spec = with(spec, "R.T", e -> e.jsonType = JsonType.TIME);
        spec = with(spec, "R.S", e -> e.jsonType = JsonType.DATE_TIME);
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:2:4: error: R.D: the JSON type DATE needs a format",
                "ORDER.cpy:3:4: error: R.T: the JSON type TIME needs a format",
                "ORDER.cpy:4:4: error: R.S: the JSON type DATE_TIME needs a format");
    }

    @Test
    void aFormatOfAnotherKindIsAnError() {
        var spec = draft("01 R.", "   05 D PIC 9(6).", "   05 T PIC 9(8).", "   05 S PIC 9(8).",
                "   05 X PIC 9(8).");
        spec = with(spec, "R.D", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "HHmmss";
        });
        spec = with(spec, "R.T", e -> {
            e.jsonType = JsonType.TIME;
            e.format = "yyyyMMdd";
        });
        spec = with(spec, "R.S", e -> {
            e.jsonType = JsonType.DATE_TIME;
            e.format = "yyyyMMdd";
        });
        spec = with(spec, "R.X", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyyMMddHHmmss";
        });
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:2:4: error: R.D: the format HHmmss is a TIME pattern, but the JSON"
                        + " type is DATE",
                "ORDER.cpy:3:4: error: R.T: the format yyyyMMdd is a DATE pattern, but the JSON"
                        + " type is TIME",
                "ORDER.cpy:4:4: error: R.S: the format yyyyMMdd is a DATE pattern, but the JSON"
                        + " type is DATE_TIME",
                "ORDER.cpy:5:4: error: R.X: the format yyyyMMddHHmmss is a DATE_TIME pattern,"
                        + " but the JSON type is DATE");
        assertThat(translation.options().dates()).isEmpty();
    }

    @Test
    void leavesAPatternThatFitsNoTypeToTheGenerator() {
        var spec = draft("01 R.", "   05 D PIC 9(8).", "   05 E PIC 9(2).");
        spec = with(spec, "R.D", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyy{";
        });
        spec = with(spec, "R.E", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "ss";
        });
        assertThat(clean(spec).options().dates()).containsExactly(Map.entry("D", "yyyy{"),
                Map.entry("E", "ss"));
    }

    @Test
    void givesTheTableOptions() {
        var spec = draft("01 R.", "   05 N PIC 9(2).", "   05 A OCCURS 3.", "      10 X PIC X.",
                "   05 B OCCURS 3.", "      10 Y PIC X.", "   05 C OCCURS 3.",
                "      10 K PIC X.", "   05 D OCCURS 3.", "      10 Z PIC X.");
        spec = with(spec, "R.A", e -> e.table = new TableSpec(TableMode.COUNT, "R.N"));
        spec = with(spec, "R.B", e -> e.table = new TableSpec(TableMode.SPACES, null));
        spec = with(spec, "R.C", e -> e.table = new TableSpec(TableMode.KEY, "R.C.K"));
        var options = clean(spec).options();
        assertThat(options.countFields()).containsExactly(Map.entry("A", "N"));
        assertThat(options.emptyItems()).containsExactly(Map.entry("B", "SPACES"));
        assertThat(options.emptyKeys()).containsExactly(Map.entry("C", "K"));
    }

    @Test
    void aTablePathOutsideTheSpecIsAnError() {
        var spec = with(draft("01 R.", "   05 A OCCURS 3.", "      10 X PIC X."), "R.A",
                e -> e.table = new TableSpec(TableMode.COUNT, "R.NOPE"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).singleElement().asString()
                .isEqualTo("ORDER.cpy:2:4: error: R.A: the path R.NOPE is not in the spec");
        assertThat(translation.options().countFields()).isEmpty();
    }

    private static final String[] AREA = {"01 R.", "   05 KIND PIC X.", "   05 A.",
        "      10 A1 PIC X(4).", "   05 B REDEFINES A.", "      10 B1 PIC 9(4)."};

    @Test
    void givesASubTypeDecisionForADiscriminator() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("1", "R.A");
        values.put("2", "R.B");
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", values));
        spec = with(spec, "R.B", e -> e.javaName = "Bee");
        Translation translation = clean(spec);
        assertThat(translation.options().subTypes()).containsExactly(Map.entry("A",
                new SubTypeDecision("KIND", Map.of("A", "1", "B", "2"), Map.of("B", "Bee"))));
        var mapped = ModelMapper.map(translation.copybook(), translation.options());
        assertThat(mapped.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
    }

    @Test
    void theMapperUsesTheJavaNameOfAnAlternativeOnce() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("1", "R.A");
        values.put("2", "R.B");
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", values));
        spec = with(spec, "R", e -> e.javaName = "Order");
        spec = with(spec, "R.B", e -> e.javaName = "Bee");
        spec = with(spec, "R.B.B1", e -> e.javaName = "number");
        Translation translation = clean(spec);
        var mapped = ModelMapper.map(translation.copybook(), translation.options(),
                translation.javaNames());
        assertThat(mapped.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
        assertThat(mapped.model().classes()).extracting(c -> c.name())
                .containsExactly("Order", "A", "Bee");
        assertThat(mapped.model().classes().get(2).members()).extracting(m -> m.name())
                .containsExactly("number");
    }

    @Test
    void aDiscriminatorWithTwoValuesForOneAlternativeIsAnError() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("1", "R.A");
        values.put("2", "R.B");
        values.put("3", "R.A");
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", values));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).singleElement().asString()
                .isEqualTo("ORDER.cpy:3:4: error: R.A: the discriminator gives the values 1"
                        + " and 3 to the alternative R.A. The generator needs one value for"
                        + " each alternative.");
        assertThat(translation.options().subTypes()).isEmpty();
    }

    @Test
    void aDiscriminatorPathOutsideTheSpecIsAnError() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.NOPE", Map.of("1", "R.A")));
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the path R.NOPE is not in the spec");
        var other = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", Map.of("1", "R.X")));
        assertThat(errors(SpecTranslator.translate(other))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the path R.X is not in the spec");
    }

    @Test
    void aDiscriminatorWithoutValuesIsAnError() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", Map.of()));
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the discriminator has no values");
    }

    @Test
    void givesDirectionsForAFixedSelection() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.B", "R.A"));
        Translation translation = clean(spec);
        assertThat(translation.options().directions()).containsOnly(
                Map.entry("B", Direction.INPUT), Map.entry("A", Direction.OUTPUT));
        var mapped = ModelMapper.map(translation.copybook(), translation.options());
        assertThat(mapped.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
    }

    @Test
    void aFixedSelectionOfOneAlternativeIsAnError() {
        var same = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.A"));
        assertThat(errors(SpecTranslator.translate(same))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the fixed selection gives the alternative R.A to"
                        + " the request and to the response. The generator needs two"
                        + " different alternatives.");
        var request = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", null));
        assertThat(errors(SpecTranslator.translate(request))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the fixed selection has no alternative for the"
                        + " response. The generator needs two different alternatives.");
        var response = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection(null, "R.B"));
        assertThat(errors(SpecTranslator.translate(response))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the fixed selection has no alternative for the"
                        + " request. The generator needs two different alternatives.");
    }

    @Test
    void aFixedSelectionPathOutsideTheSpecIsAnError() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.Z"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the path R.Z is not in the spec");
        assertThat(translation.options().directions()).isEmpty();
        var request = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.Z", "R.A"));
        assertThat(errors(SpecTranslator.translate(request))).containsExactly(
                "ORDER.cpy:3:4: error: R.A: the path R.Z is not in the spec");
    }

    @Test
    void aNodeWithoutUsageInheritsTheUsageOfItsGroup() {
        var spec = draft("01 R.", "   05 G COMP-3.", "      10 N PIC S9(5).",
                "   05 H.", "      10 M PIC S9(5).");
        spec = with(spec, "R.G.N", e -> {
            e.usage = null;
            e.sign = Sign.TRAILING;
        });
        spec = with(spec, "R.H.M", e -> e.usage = null);
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly("ORDER.cpy:3:7: error: R.G.N: the sign"
                + " TRAILING needs a signed DISPLAY picture without edit symbols");
        Copybook copybook = translation.copybook();
        assertThat(item(copybook, "R.G.N").usage()).isNull();
        assertThat(item(copybook, "R.H.M").sign()).isNull();
    }

    @Test
    void aTableDecisionOnADuplicateSiblingIsAnError() {
        var spec = draft("01 R.", "   05 N PIC 9(2).", "   05 N PIC 9(2).", "   05 A OCCURS 3.",
                "      10 X PIC X.", "   05 A OCCURS 3.", "      10 Y PIC X.");
        spec = with(spec, "R.A", e -> e.table = new TableSpec(TableMode.COUNT, "R.N#2"));
        spec = with(spec, "R.A#2", e -> e.table = new TableSpec(TableMode.SPACES, null));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:4:4: error: R.A: no COBOL name designates only the item R.A. A"
                        + " FILLER or a duplicate sibling name cannot be named.",
                "ORDER.cpy:4:4: error: R.A: no COBOL name designates only the item R.N#2. A"
                        + " FILLER or a duplicate sibling name cannot be named.",
                "ORDER.cpy:6:4: error: R.A#2: no COBOL name designates only the item R.A#2. A"
                        + " FILLER or a duplicate sibling name cannot be named.");
        assertThat(translation.options().countFields()).isEmpty();
        assertThat(translation.options().emptyItems()).isEmpty();
    }

    @Test
    void aCounterWithoutAUniqueNameIsAnError() {
        var spec = draft("01 R.", "   05 N PIC 9(2).", "   05 N PIC 9(2).", "   05 A OCCURS 3.",
                "      10 X PIC X.");
        spec = with(spec, "R.A", e -> e.table = new TableSpec(TableMode.KEY, "R.N#2"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:4:4: error: R.A: no COBOL name designates only the item R.N#2. A"
                        + " FILLER or a duplicate sibling name cannot be named.");
        assertThat(translation.options().emptyKeys()).isEmpty();
    }

    private static final String[] FILLER_AREA = {"01 R.", "   05 KIND PIC X.",
        "   05 FILLER PIC X.", "   05 A.", "      10 A1 PIC X(4).", "   05 FILLER REDEFINES A.",
        "      10 B1 PIC 9(4)."};

    @Test
    void aVariantWithoutUniqueNamesIsAnError() {
        String message = ". A FILLER or a duplicate sibling name cannot be named.";
        var alternative = with(draft(FILLER_AREA), "R.A", e -> e.variant =
                new VariantSpec.Discriminator("R.KIND", Map.of("1", "R.A", "2", "R.FILLER#2")));
        Translation one = SpecTranslator.translate(alternative);
        assertThat(errors(one)).containsExactly("ORDER.cpy:4:4: error: R.A: no COBOL name"
                + " designates only the item R.FILLER#2" + message);
        assertThat(one.options().subTypes()).isEmpty();
        var field = with(draft(FILLER_AREA), "R.A", e -> e.variant =
                new VariantSpec.Discriminator("R.FILLER", Map.of("1", "R.A")));
        Translation two = SpecTranslator.translate(field);
        assertThat(errors(two)).containsExactly("ORDER.cpy:4:4: error: R.A: no COBOL name"
                + " designates only the item R.FILLER" + message);
        assertThat(two.options().subTypes()).isEmpty();
        var request = with(draft(FILLER_AREA), "R.A", e -> e.variant =
                new VariantSpec.FixedSelection("R.FILLER#2", "R.A"));
        Translation three = SpecTranslator.translate(request);
        assertThat(errors(three)).containsExactly("ORDER.cpy:4:4: error: R.A: no COBOL name"
                + " designates only the item R.FILLER#2" + message);
        assertThat(three.options().directions()).isEmpty();
        var response = with(draft(FILLER_AREA), "R.A", e -> e.variant =
                new VariantSpec.FixedSelection("R.A", "R.FILLER#2"));
        assertThat(SpecTranslator.translate(response).options().directions()).isEmpty();
    }

    @Test
    void aVariantOnAnAlternativeDecidesTheArea() {
        var spec = with(draft(AREA), "R.B",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        assertThat(clean(spec).options().directions()).containsOnly(
                Map.entry("A", Direction.INPUT), Map.entry("B", Direction.OUTPUT));
    }

    @Test
    void aVariantOutsideAnAreaIsAnError() {
        var single = with(draft(AREA), "R.KIND",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        assertThat(errors(SpecTranslator.translate(single))).containsExactly(
                "ORDER.cpy:2:4: error: R.KIND: the variant is not on a REDEFINES area",
                "ORDER.cpy:3:4: error: R.A: the REDEFINES area has no variant. Set a"
                        + " discriminator or a fixed selection.");
        var text = with(draft(AREA), "R.B", e -> {
            e.redefines = "A";
            e.variant = new VariantSpec.FixedSelection("R.A", "R.B");
        });
        assertThat(errors(SpecTranslator.translate(text))).containsExactly(
                "ORDER.cpy:5:4: error: R.B: the variant is not on a REDEFINES area");
    }

    @Test
    void aSecondVariantOnOneAreaIsAnError() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.Discriminator("R.KIND", Map.of("1", "R.A")));
        spec = with(spec, "R.B", e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly("ORDER.cpy:5:4: error: R.B: the"
                + " REDEFINES area of R.A already has a variant on R.A. Set one variant for"
                + " each area.");
        assertThat(translation.options().directions()).isEmpty();
    }

    @Test
    void anAlternativeOutsideTheAreaIsAnError() {
        var spec = with(draft(AREA), "R.A", e -> e.variant =
                new VariantSpec.Discriminator("R.KIND", Map.of("1", "R.A", "2", "R.KIND")));
        Translation one = SpecTranslator.translate(spec);
        assertThat(errors(one)).containsExactly("ORDER.cpy:3:4: error: R.A: the alternative"
                + " R.KIND is not in the REDEFINES area of R.A");
        assertThat(one.options().subTypes()).isEmpty();
        var fixed = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.KIND", "R.B"));
        Translation two = SpecTranslator.translate(fixed);
        assertThat(errors(two)).containsExactly("ORDER.cpy:3:4: error: R.A: the alternative"
                + " R.KIND is not in the REDEFINES area of R.A");
        assertThat(two.options().directions()).isEmpty();
    }

    @Test
    void aFixedSelectionInAnAreaOfThreeIsAnError() {
        var spec = draft("01 R.", "   05 A.", "      10 A1 PIC X.", "   05 B REDEFINES A.",
                "      10 B1 PIC X.", "   05 C REDEFINES A.", "      10 C1 PIC X.");
        spec = with(spec, "R.A", e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly("ORDER.cpy:2:4: error: R.A: the"
                + " REDEFINES area has 3 alternatives. A fixed selection needs an area of two"
                + " alternatives. Set a discriminator.");
        assertThat(translation.options().directions()).isEmpty();
    }

    @Test
    void anAreaWithoutAVariantIsAnError() {
        var spec = draft("01 R.", "   05 A.", "      10 A1 PIC X.", "   05 B REDEFINES A.",
                "      10 B1 PIC X.", "   05 E PIC X(2).", "   05 F REDEFINES E PIC 99.");
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly("ORDER.cpy:2:4:"
                + " error: R.A: the REDEFINES area has no variant. Set a discriminator or a"
                + " fixed selection.");
    }

    @Test
    void aVariantOnAnAreaWithAnElementaryAlternativeIsAnError() {
        String groups = " is not a group. The generator decides only an area of groups.";
        var elementary = with(draft("01 R.", "   05 KIND PIC X.", "   05 E PIC X(2).",
                "   05 F REDEFINES E PIC 99."), "R.E",
                e -> e.variant = new VariantSpec.FixedSelection("R.E", "R.F"));
        Translation one = SpecTranslator.translate(elementary);
        assertThat(errors(one)).containsExactly("ORDER.cpy:3:4: error: R.E: the alternative R.E"
                + " of the REDEFINES area of R.E" + groups);
        assertThat(one.options().directions()).isEmpty();
        var mixed = with(draft("01 R.", "   05 KIND PIC X.", "   05 A.", "      10 A1 PIC X(4).",
                "   05 B REDEFINES A PIC 9(4)."), "R.A", e -> e.variant =
                new VariantSpec.Discriminator("R.KIND", Map.of("1", "R.A", "2", "R.B")));
        Translation two = SpecTranslator.translate(mixed);
        assertThat(errors(two)).containsExactly("ORDER.cpy:3:4: error: R.A: the alternative R.B"
                + " of the REDEFINES area of R.A" + groups);
        assertThat(two.options().subTypes()).isEmpty();
    }

    @Test
    void aVariantOnAnAreaInsideAnAlternativeIsAnError() {
        var spec = draft("01 R.", "   05 A.", "      10 X.", "         15 X1 PIC X.",
                "      10 Y REDEFINES X.", "         15 Y1 PIC X.", "   05 B REDEFINES A.",
                "      10 Z PIC X.");
        spec = with(spec, "R.A", e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        spec = with(spec, "R.A.Y",
                e -> e.variant = new VariantSpec.FixedSelection("R.A.X", "R.A.Y"));
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly("ORDER.cpy:5:7: error: R.A.Y: the"
                + " REDEFINES area of R.A.X is inside an alternative of another area. The"
                + " generator keeps all alternatives of an inner area.");
        assertThat(translation.options().directions()).containsOnlyKeys("A", "B");
    }

    @Test
    void anAreaInsideAnAlternativeNeedsNoVariant() {
        var spec = draft("01 R.", "   05 A.", "      10 X.", "         15 X1 PIC X.",
                "      10 Y REDEFINES X.", "         15 Y1 PIC X.", "   05 B REDEFINES A.",
                "      10 Z.", "         15 Z1 PIC X.", "      10 W REDEFINES Z.",
                "         15 W1 PIC X.");
        spec = with(spec, "R.A", e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        assertThat(clean(spec).options().directions()).hasSize(2);
    }

    @Test
    void ignoresADirectionInsideAnArea() {
        var spec = with(draft(AREA), "R.A",
                e -> e.variant = new VariantSpec.FixedSelection("R.A", "R.B"));
        var directed = with(with(spec, "R.A.A1", e -> e.direction = FieldDirection.OUTPUT),
                "R.B", e -> e.direction = FieldDirection.INPUT);
        assertThat(clean(directed).options()).isEqualTo(clean(spec).options());
    }

    @Test
    void ignoresTheDirectionAndTheJsonName() {
        var spec = with(draft("01 R.", "   05 A PIC X."), "R.A", e -> {
            e.direction = FieldDirection.OUTPUT;
            e.jsonName = "other";
            e.jsonType = JsonType.DECIMAL_STRING;
        });
        Translation translation = clean(spec);
        assertThat(translation.options()).isEqualTo(
                clean(draft("01 R.", "   05 A PIC X.")).options());
    }

    // ---- names of the options ----

    @Test
    void qualifiesAmbiguousNames() {
        var spec = draft("01 R.", "   05 G1.", "      10 CNT PIC 9(2).",
                "      10 T OCCURS 3.", "         15 X PIC X.", "   05 G2.",
                "      10 CNT PIC 9(2).");
        spec = with(spec, "R.G1.T", e -> e.table = new TableSpec(TableMode.COUNT, "R.G1.CNT"));
        assertThat(clean(spec).options().countFields())
                .containsExactly(Map.entry("T", "CNT OF G1"));
    }

    @Test
    void qualifiesBeyondTheParentWhenNeeded() {
        var spec = draft("01 R.", "   05 G1.", "      10 S.", "         15 CNT PIC 9(2).",
                "      10 T OCCURS 3.", "         15 X PIC X.", "   05 G2.", "      10 S.",
                "         15 CNT PIC 9(2).");
        spec = with(spec, "R.G1.T", e -> e.table = new TableSpec(TableMode.COUNT, "R.G1.S.CNT"));
        assertThat(clean(spec).options().countFields())
                .containsExactly(Map.entry("T", "CNT OF S OF G1"));
    }

    @Test
    void skipsAFillerQualifier() {
        var spec = draft("01 R.", "   05 G.", "      10 FILLER.", "         15 D PIC 9(8).",
                "   05 H.", "      10 D PIC 9(8).");
        spec = with(spec, "R.G.FILLER.D", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyyMMdd";
        });
        assertThat(clean(spec).options().dates()).containsExactly(Map.entry("D OF G",
                "yyyyMMdd"));
        assertThat(item(clean(spec).copybook(), "R.G.FILLER.D").name()).isEqualTo("D");
    }

    @Test
    void duplicateSiblingDecisionIsAnError() {
        var spec = draft("01 R.", "   05 D PIC 9(8).", "   05 D PIC 9(8).");
        spec = with(spec, "R.D#2", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyyMMdd";
        });
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly("ORDER.cpy:3:4: error: R.D#2: no COBOL"
                + " name designates only the item R.D#2. A FILLER or a duplicate sibling name"
                + " cannot be named.");
        assertThat(translation.options().dates()).isEmpty();
    }

    @Test
    void aDecisionOnAFillerIsAnError() {
        var spec = with(draft("01 R.", "   05 FILLER PIC 9(8)."), "R.FILLER", e -> {
            e.jsonType = JsonType.DATE;
            e.format = "yyyyMMdd";
        });
        assertThat(errors(SpecTranslator.translate(spec))).containsExactly(
                "ORDER.cpy:2:4: error: R.FILLER: no COBOL name designates only the item"
                        + " R.FILLER. A FILLER or a duplicate sibling name cannot be named.");
    }

    // ---- Java names ----

    @Test
    void collectsTheJavaNames() {
        var spec = draft("01 R.", "   05 G.", "      10 A PIC X.");
        spec = with(spec, "R", e -> e.javaName = "Order");
        spec = with(spec, "R.G.A", e -> e.javaName = "alpha");
        Translation translation = clean(spec);
        Copybook copybook = translation.copybook();
        assertThat(translation.javaNames()).hasSize(2)
                .containsEntry(item(copybook, "R"), "Order")
                .containsEntry(item(copybook, "R.G.A"), "alpha");
        assertThatThrownBy(() -> translation.javaNames().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keysTheJavaNamesByIdentity() {
        var spec = draft("01 R.", "   05 A PIC X.", "   05 B PIC X.");
        spec = with(spec, "R.A", e -> e.javaName = "first");
        Translation translation = clean(spec);
        DataItem a = item(translation.copybook(), "R.A");
        DataItem equal = new DataItem(a.level(), a.name(), a.picture(), a.usage(), a.occurs(),
                a.redefines(), a.conditions(), a.children(), a.source(), a.line(), a.column(),
                a.declaration(), a.value(), a.sign());
        assertThat(translation.javaNames()).doesNotContainKey(equal);
    }

    @Test
    void aJavaNameOnAFillerIsAnError() {
        var spec = draft("01 R.", "   05 FILLER.", "      10 A PIC X.", "   05 FILLER PIC X.");
        spec = with(spec, "R.FILLER", e -> e.javaName = "group");
        spec = with(spec, "R.FILLER.A", e -> e.javaName = "inner");
        spec = with(spec, "R.FILLER#2", e -> e.javaName = "pad");
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:2:4: error: R.FILLER: the Java name group is on a FILLER. The"
                        + " generator gives no Java name to a FILLER.",
                "ORDER.cpy:3:7: error: R.FILLER.A: the Java name inner is in a FILLER group."
                        + " The generator puts the items of a FILLER group in the enclosing"
                        + " class, and a FILLER has no stable path for a decision.",
                "ORDER.cpy:4:4: error: R.FILLER#2: the Java name pad is on a FILLER. The"
                        + " generator gives no Java name to a FILLER.");
        assertThat(translation.javaNames()).isEmpty();
    }

    @Test
    void aJavaNameThatIsNotAnIdentifierIsAnError() {
        var spec = draft("01 R.", "   05 A PIC X.", "   05 B PIC X.", "   05 C PIC X.");
        spec = with(spec, "R.A", e -> e.javaName = "1abc");
        spec = with(spec, "R.B", e -> e.javaName = "class");
        spec = with(spec, "R.C", e -> e.javaName = "a.b");
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:2:4: error: R.A: the Java name 1abc is not a Java identifier",
                "ORDER.cpy:3:4: error: R.B: the Java name class is not a Java identifier",
                "ORDER.cpy:4:4: error: R.C: the Java name a.b is not a Java identifier");
        assertThat(translation.javaNames()).isEmpty();
    }

    @Test
    void aJavaNameOnADuplicateSiblingIsAnError() {
        var spec = with(draft("01 R.", "   05 CODE PIC X.", "   05 CODE PIC X."), "R.CODE#2",
                e -> e.javaName = "secondCode");
        Translation translation = SpecTranslator.translate(spec);
        assertThat(errors(translation)).containsExactly(
                "ORDER.cpy:3:4: error: R.CODE#2: the Java name secondCode is on a duplicate"
                        + " sibling name. Rename the COBOL item.");
        assertThat(translation.javaNames()).isEmpty();
    }

    @Test
    void translationCopiesItsLists() {
        var spec = draft("01 R.", "   05 A PIC X.");
        Translation translation = clean(spec);
        var copy = new Translation(translation.copybook(), translation.options(),
                new java.util.HashMap<>(), new ArrayList<>(List.of(new Diagnostic(
                        Severity.WARNING, "X", 1, 1, "note"))));
        assertThat(copy.hasErrors()).isFalse();
        assertThatThrownBy(() -> copy.diagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
