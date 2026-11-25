package pt.dazz.copymapper.copybook.map;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.dazz.copymapper.copybook.map.Mapping.diagnostics;
import static pt.dazz.copymapper.copybook.map.Mapping.member;
import static pt.dazz.copymapper.copybook.map.Mapping.type;

import com.palantir.javapoet.JavaFile;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pt.dazz.copymapper.annotation.Direction;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.ast.DataItem;
import pt.dazz.copymapper.copybook.diag.Diagnostic;
import pt.dazz.copymapper.copybook.diag.Severity;
import pt.dazz.copymapper.copybook.generate.GenerateOptions;
import pt.dazz.copymapper.copybook.generate.JavaClassGenerator;
import pt.dazz.copymapper.copybook.model.ClassKind;
import pt.dazz.copymapper.copybook.model.ClassModel;
import pt.dazz.copymapper.copybook.model.CollectionMember;
import pt.dazz.copymapper.copybook.model.GroupMember;
import pt.dazz.copymapper.copybook.model.MemberModel;
import pt.dazz.copymapper.copybook.model.SubTypeEntry;
import pt.dazz.copymapper.copybook.parse.CopybookParser;
import pt.dazz.copymapper.copybook.parse.ParseOptions;
import pt.dazz.copymapper.copybook.parse.ParseResult;
import pt.dazz.copymapper.copybook.parse.SourceFormat;

/** Tests the Java names that a mapping spec gives to the items of a copybook. */
class JavaNameOverrideTest {

    /** A copybook and the Java names of some of its items. */
    private static final class Named {
        final Copybook copybook;
        final Map<DataItem, String> javaNames = new IdentityHashMap<>();

        Named(String... lines) {
            ParseResult parsed = CopybookParser.parse("ORDER.cpy", String.join("\n", lines),
                new ParseOptions(SourceFormat.FREE, StandardCharsets.UTF_8, List.of(), null));
            assertThat(parsed.hasErrors()).as(parsed.diagnostics().toString()).isFalse();
            copybook = parsed.copybook();
        }

        /** Gives a Java name to the item at a path of COBOL names, for example R.G.A. */
        Named name(String path, String javaName) {
            String[] parts = path.split("\\.");
            DataItem item = copybook.records().get(0);
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                item = item.children().stream().filter(c -> c.name().equals(part))
                    .findFirst().orElseThrow();
            }
            javaNames.put(item, javaName);
            return this;
        }

        MapResult map(MappingOptions options) {
            return ModelMapper.map(copybook, options, javaNames);
        }

        MapResult map() {
            return map(MappingOptions.defaults());
        }
    }

    private static List<String> errors(MapResult result) {
        return result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR)
            .map(Diagnostic::format).toList();
    }

    private static List<String> classNames(MapResult result) {
        return result.model().classes().stream().map(ClassModel::name).toList();
    }

    private static MappingOptions options(Map<String, Direction> directions,
                                          Map<String, SubTypeDecision> subTypes) {
        return new MappingOptions(List.of(), MappingOptions.INPUT_MARKERS,
            MappingOptions.OUTPUT_MARKERS, directions, subTypes);
    }

    private static MappingOptions views(String name) {
        MappingOptions base = MappingOptions.defaults();
        return new MappingOptions(base.stripPrefixes(), base.inputMarkers(),
            base.outputMarkers(), base.directions(), base.subTypes(), base.countFields(),
            base.maxRecordLength(), base.dates(), base.continuable(), base.validatable(),
            base.conditions(), base.emptyItems(), base.emptyKeys(), base.partialTables(),
            List.of(name));
    }

    // ---- the classes ----

    @Test
    void theRecordGetsItsJavaName() {
        MapResult result = new Named("01 ORDER-REC.", "   05 A PIC X.")
            .name("ORDER-REC", "PurchaseOrder").map();
        assertThat(diagnostics(result)).isEmpty();
        assertThat(classNames(result)).containsExactly("PurchaseOrder");
    }

    @Test
    void aTableGetsTheClassNameAndKeepsTheFieldName() {
        MapResult result = new Named("01 R.", "   05 LINES OCCURS 3.", "      10 QTY PIC 9.",
            "   05 CODES PIC X OCCURS 2.")
            .name("R.LINES", "OrderLine").name("R.CODES", "Code").map();
        assertThat(diagnostics(result)).isEmpty();
        assertThat(classNames(result)).containsExactly("R", "OrderLine", "Code");
        ClassModel record = type(result, "R");
        assertThat(((CollectionMember) member(record, "lines")).itemClass())
            .isEqualTo("OrderLine");
        assertThat(((CollectionMember) member(record, "codes")).itemClass()).isEqualTo("Code");
        assertThat(type(result, "Code").members()).extracting(MemberModel::name)
            .containsExactly("value");
    }

    @Test
    void aGroupOfAViewAreaGetsTheClassName() {
        MapResult result = new Named("01 R.", "   05 A PIC X(4).", "   05 B REDEFINES A.",
            "      10 B1 PIC X(2).", "      10 B2 PIC X(2).")
            .name("R.B", "Parts").map(views("A"));
        assertThat(diagnostics(result)).isEmpty();
        assertThat(((GroupMember) member(type(result, "R"), "b")).groupClass())
            .isEqualTo("Parts");
        assertThat(type(result, "Parts").kind()).isEqualTo(ClassKind.GROUP);
    }

    @Test
    void anAlternativeGetsTheClassName() {
        // The translator also puts the name of an alternative in the subtype decision.
        SubTypeDecision decision = new SubTypeDecision("KIND", Map.of("A", "1", "B", "2"),
            Map.of("B", "Bee"));
        MapResult result = new Named("01 R.", "   05 KIND PIC X.", "   05 A.",
            "      10 A1 PIC X(4).", "   05 B REDEFINES A.", "      10 B1 PIC 9(4).")
            .name("R.A", "Ay").name("R.B", "Bee").map(options(Map.of(), Map.of("A", decision)));
        assertThat(diagnostics(result)).isEmpty();
        assertThat(type(result, "R").subTypes().types()).extracting(SubTypeEntry::className)
            .containsExactly("Ay", "Bee");
    }

    @Test
    void anAlternativeWithoutAJavaNameKeepsTheDecisionName() {
        SubTypeDecision decision = new SubTypeDecision("KIND", Map.of("A", "1", "B", "2"),
            Map.of("B", "Bee"));
        MapResult result = new Named("01 R.", "   05 KIND PIC X.", "   05 A.",
            "      10 A1 PIC X(4).", "   05 B REDEFINES A.", "      10 B1 PIC 9(4).")
            .map(options(Map.of(), Map.of("A", decision)));
        assertThat(type(result, "R").subTypes().types()).extracting(SubTypeEntry::className)
            .containsExactly("A", "Bee");
    }

    // ---- the fields ----

    @Test
    void aFieldGetsItsJavaName() {
        MapResult result = new Named("01 R.", "   05 G.", "      10 A PIC X.", "   05 B PIC 9.")
            .name("R.G.A", "alpha").map();
        assertThat(diagnostics(result)).isEmpty();
        assertThat(type(result, "R").members()).extracting(MemberModel::name)
            .containsExactly("alpha", "b");
    }

    @Test
    void aDerivedNameAfterAJavaNameGetsANumber() {
        MapResult result = new Named("01 R.", "   05 A PIC X.", "   05 B PIC X.")
            .name("R.A", "b").map();
        assertThat(diagnostics(result)).isEmpty();
        assertThat(type(result, "R").members()).extracting(MemberModel::name)
            .containsExactly("b", "b2");
    }

    @Test
    void theGeneratedClassesUseTheJavaNames() {
        MapResult result = new Named("01 ORDER-REC.", "   05 ORDER-ID PIC X(4).",
            "   05 LINES OCCURS 2.", "      10 QTY PIC 9.")
            .name("ORDER-REC", "PurchaseOrder").name("ORDER-REC.ORDER-ID", "id")
            .name("ORDER-REC.LINES", "OrderLine").map();
        List<String> sources = JavaClassGenerator.generate(result.model(),
            new GenerateOptions("com.example", false)).stream().map(JavaFile::toString).toList();
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).contains("public class PurchaseOrder")
            .contains("private String id;").contains("List<OrderLine> lines");
        assertThat(sources.get(1)).contains("public class OrderLine");
    }

    // ---- the errors ----

    @Test
    void aFieldNameThatIsInUseIsAnError() {
        MapResult result = new Named("01 R.", "   05 A PIC X.", "   05 B PIC X.")
            .name("R.B", "a").map();
        assertThat(result.model()).isNull();
        assertThat(errors(result)).containsExactly("ORDER.cpy:3:4: error: the Java name a of B"
            + " is already used in class R");
    }

    @Test
    void aFieldNameOfABaseClassIsInUseInASubtype() {
        SubTypeDecision decision = new SubTypeDecision("KIND", Map.of("A", "1", "B", "2"),
            Map.of());
        MapResult result = new Named("01 R.", "   05 KIND PIC X.", "   05 A.",
            "      10 A1 PIC X(4).", "   05 B REDEFINES A.", "      10 B1 PIC 9(4).")
            .name("R.B.B1", "kind").map(options(Map.of(), Map.of("A", decision)));
        assertThat(errors(result)).containsExactly("ORDER.cpy:6:7: error: the Java name kind"
            + " of B1 is already used in class B");
    }

    @Test
    void aClassNameThatIsInUseIsAnError() {
        MapResult result = new Named("01 ORDER-REC.", "   05 LINES OCCURS 3.",
            "      10 QTY PIC 9.")
            .name("ORDER-REC.LINES", "orderRec").map();
        assertThat(result.model()).isNull();
        assertThat(errors(result)).containsExactly("ORDER.cpy:2:4: error: the Java name"
            + " orderRec of LINES is already the name of another class or enum");
    }

    @Test
    void aJavaNameWithoutAClassOrAFieldIsAnError() {
        MapResult result = new Named("01 R.", "   05 G.", "      10 A PIC X(4).",
            "      10 A2 REDEFINES A PIC 9(4).", "   05 IN-DATA.", "      10 I PIC X.",
            "   05 OUT-DATA REDEFINES IN-DATA.", "      10 O PIC X.")
            .name("R.IN-DATA", "Input").name("R.G.A2", "number").name("R.G", "Group")
            .name("R.OUT-DATA", "Output").map();
        assertThat(errors(result)).containsExactly(
            "ORDER.cpy:2:4: error: the Java name Group of G is not used. The generator makes"
                + " no class or field for G.",
            "ORDER.cpy:4:7: error: the Java name number of A2 is not used. The generator makes"
                + " no class or field for A2.",
            "ORDER.cpy:5:4: error: the Java name Input of IN-DATA is not used. The generator"
                + " makes no class or field for IN-DATA.",
            "ORDER.cpy:7:4: error: the Java name Output of OUT-DATA is not used. The generator"
                + " makes no class or field for OUT-DATA.");
    }

    @Test
    void aSkippedAlternativeOfANestedAreaHasNoClass() {
        MapResult result = new Named("01 R.", "   05 KIND PIC X.", "   05 A.",
            "      10 X.", "         15 X1 PIC X(2).", "      10 Y REDEFINES X.",
            "         15 Y1 PIC X(2).", "   05 B REDEFINES A.", "      10 B1 PIC 9(2).")
            .name("R.A.Y", "Why")
            .map(options(Map.of(), Map.of("A", new SubTypeDecision("KIND",
                Map.of("A", "1", "B", "2"), Map.of()))));
        assertThat(errors(result)).containsExactly("ORDER.cpy:6:7: error: the Java name Why"
            + " of Y is not used. The generator makes no class or field for Y.");
    }

    @Test
    void anItemWithAnErrorDoesNotReportItsJavaName() {
        MapResult result = new Named("01 R.", "   05 A PIC X COMP.",
            "   05 T PIC 9 OCCURS 2 DEPENDING ON NOPE.")
            .name("R.A", "alpha").name("R.T", "Tee").map();
        assertThat(errors(result)).containsExactly(
            "ORDER.cpy:2:4: error: A has a text picture and must not have COMP or COMP-3",
            "ORDER.cpy:3:4: error: DEPENDING ON item NOPE does not exist");
    }

    @Test
    void aClassNameOfJavaLangIsAnError() {
        MapResult result = new Named("01 R.", "   05 LINES OCCURS 2.", "      10 QTY PIC 9.",
            "   05 CODES PIC X OCCURS 2.")
            .name("R", "String").name("R.LINES", "Record").name("R.CODES", "Object").map();
        assertThat(errors(result)).containsExactly(
            "ORDER.cpy:1:1: error: the Java name String of R is the name of a java.lang class",
            "ORDER.cpy:2:4: error: the Java name Record of LINES is the name of a java.lang"
                + " class",
            "ORDER.cpy:4:4: error: the Java name Object of CODES is the name of a java.lang"
                + " class");
    }

    @Test
    void aJavaLangNameIsAValidFieldName() {
        MapResult result = new Named("01 R.", "   05 A PIC X.").name("R.A", "String").map();
        assertThat(diagnostics(result)).isEmpty();
        assertThat(type(result, "R").members()).extracting(MemberModel::name)
            .containsExactly("String");
    }

    @Test
    void aRestrictedNameIsAnErrorForAClass() {
        MapResult result = new Named("01 R.", "   05 LINES OCCURS 2.", "      10 QTY PIC 9.",
            "   05 V PIC X.")
            .name("R.LINES", "var").name("R.V", "var").map();
        assertThat(errors(result)).containsExactly("ORDER.cpy:2:4: error: the Java name var of"
            + " LINES is a restricted name and cannot be a class name");
    }

    @Test
    void aJavaNameThatIsNotAnIdentifierIsAnError() {
        MapResult result = new Named("01 R.", "   05 A PIC X.", "   05 B PIC X.",
            "   05 LINES OCCURS 2.", "      10 QTY PIC 9.", "   05 G.", "      10 C PIC X.")
            .name("R.A", "my-name").name("R.B", "class").name("R.LINES", "a.Line")
            .name("R.G", "").map();
        assertThat(result.model()).isNull();
        assertThat(errors(result)).containsExactly(
            "ORDER.cpy:2:4: error: the Java name my-name of A is not a Java identifier",
            "ORDER.cpy:3:4: error: the Java name class of B is not a Java identifier",
            "ORDER.cpy:4:4: error: the Java name a.Line of LINES is not a Java identifier",
            "ORDER.cpy:6:4: error: the Java name  of G is not a Java identifier");
    }

    @Test
    void aJavaNameOnAFillerIsAnError() {
        Named named = new Named("01 R.", "   05 FILLER PIC X.", "   05 A PIC X.");
        named.javaNames.put(named.copybook.records().get(0).children().get(0), "pad");
        MapResult result = named.map();
        assertThat(errors(result)).containsExactly("ORDER.cpy:2:4: error: the Java name pad"
            + " is on a FILLER. The generator gives no Java name to a FILLER.");
    }
}
