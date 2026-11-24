package pt.dazz.copymapper.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.dazz.copymapper.copybook.Compiled.get;
import static pt.dazz.copymapper.copybook.Compiled.set;
import static pt.dazz.copymapper.copybook.EndToEndTest.copybook;
import static pt.dazz.copymapper.copybook.EndToEndTest.mappingOptions;
import static pt.dazz.copymapper.copybook.EndToEndTest.parseOptions;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pt.dazz.copymapper.annotation.Direction;
import pt.dazz.copymapper.convert.CodecContext;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.ast.DataItem;
import pt.dazz.copymapper.copybook.config.GeneratorConfigJson;
import pt.dazz.copymapper.copybook.diag.Diagnostic;
import pt.dazz.copymapper.copybook.diag.Severity;
import pt.dazz.copymapper.copybook.generate.GenerateOptions;
import pt.dazz.copymapper.copybook.map.ConditionMode;
import pt.dazz.copymapper.copybook.map.MappingOptions;
import pt.dazz.copymapper.copybook.parse.CopybookParser;
import pt.dazz.copymapper.copybook.spec.DraftOptions;
import pt.dazz.copymapper.copybook.spec.JsonType;
import pt.dazz.copymapper.copybook.spec.MappingSpec;
import pt.dazz.copymapper.copybook.spec.MappingSpecs;
import pt.dazz.copymapper.copybook.spec.SpecNode;
import pt.dazz.copymapper.copybook.spec.TableMode;
import pt.dazz.copymapper.copybook.spec.TableSpec;
import pt.dazz.copymapper.copybook.spec.VariantSpec;
import pt.dazz.copymapper.spi.BookCodec;

/**
 * Checks that a mapping spec gives the classes of its copybook. For each test copybook and
 * its options, the test drafts the spec of each record, sets the REST decisions that are
 * equal to the options, and compares the generated sources.
 */
class SpecParityTest {

    private static final String PACKAGE = "com.example.generated";
    private static final GenerateOptions PLAIN = new GenerateOptions(PACKAGE, false);
    private static final CodecContext LATIN = new CodecContext(StandardCharsets.ISO_8859_1);

    /**
     * One parity case: a copybook, its options, and the spec decisions that are equal to the
     * options, by spec path.
     */
    record Case(String label, String copybook, MappingOptions options,
                Map<String, UnaryOperator<SpecNode>> decisions) {

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A copybook with options that a mapping spec cannot express, and the reason. The test
     * checks that the spec gives other classes, so the list stays true.
     */
    record NotExpressible(String label, String copybook, MappingOptions options,
                          String reason) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
            new Case("ACCOUNT.cpy: subtypes", "ACCOUNT.cpy", mappingOptions("ACCOUNT.cpy"),
                new Decisions().on("GET-ACCOUNT.CHECKING-DATA", variant(account())).map()),
            new Case("ACCOUNT.cpy with account-config.json: subtypes and a class name",
                "ACCOUNT.cpy", config("account-config.json"), new Decisions()
                .on("GET-ACCOUNT.CHECKING-DATA", variant(account()))
                .on("GET-ACCOUNT.SAVINGS-DATA", javaName("SavingsAccount")).map()),
            new Case("INVOICE.cpy: name markers", "INVOICE.cpy", mappingOptions("INVOICE.cpy"),
                new Decisions().on("GET-INVOICE.INVOICE-REQUEST", variant(invoice())).map()),
            new Case("INVOICE.cpy: explicit directions", "INVOICE.cpy",
                new MappingOptions(List.of(), List.of(), List.of(), Map.of(
                    "INVOICE-REQUEST", Direction.INPUT, "INVOICE-RESPONSE", Direction.OUTPUT),
                    Map.of()),
                new Decisions().on("GET-INVOICE.INVOICE-REQUEST", variant(invoice())).map()),
            new Case("INVOICE.cpy: name markers and a date", "INVOICE.cpy",
                mappingOptions("INVOICE.cpy").withHints(Map.of("*-DATE", "yyyyMMdd"),
                    Map.of(), Map.of()), new Decisions()
                .on("GET-INVOICE.INVOICE-REQUEST", variant(invoice()))
                .on("GET-INVOICE.INVOICE-RESPONSE.DUE-DATE", date(JsonType.DATE, "yyyyMMdd"))
                .map()),
            new Case("INVOICE-PARTY.cpy: level 88 items", "INVOICE-PARTY.cpy",
                mappingOptions("INVOICE-PARTY.cpy"), Map.of()),
            new Case("INVOICE-PRINT.cpy: edited pictures", "INVOICE-PRINT.cpy",
                mappingOptions("INVOICE-PRINT.cpy"), Map.of()),
            new Case("LIST-SHIPMENTS.cpy: name markers and dates", "LIST-SHIPMENTS.cpy",
                mappingOptions("LIST-SHIPMENTS.cpy").withHints(shipmentDates(), Map.of(),
                    Map.of()), new Decisions()
                .on("LIST-SHIPMENTS.SHIPMENT-REQUEST", variant(shipments()))
                .on("LIST-SHIPMENTS.SHIPMENT-REQUEST.FROM-DATE",
                    date(JsonType.DATE, "yyyyMMdd"))
                .on("LIST-SHIPMENTS.SHIPMENT-RESPONSE.SHIPMENT-ENTRY.SHIP-DATE",
                    date(JsonType.DATE, "yyyyMMdd"))
                .on("LIST-SHIPMENTS.SHIPMENT-RESPONSE.SHIPMENT-ENTRY.SHIP-TIME",
                    date(JsonType.TIME, "HH:mm:ss"))
                .on("LIST-SHIPMENTS.SHIPMENT-RESPONSE.SHIPMENT-ENTRY.LAST-UPDATE",
                    date(JsonType.DATE_TIME, "yyyyMMddHHmmss")).map()),
            new Case("ORDER-TAXES.cpy: subtypes in a table and a count field",
                "ORDER-TAXES.cpy", mappingOptions("ORDER-TAXES.cpy"), new Decisions()
                .on("GET-PAYMENTS.PAYMENT.CARD-DATA", variant(new VariantSpec.Discriminator(
                    "GET-PAYMENTS.PAYMENT.PAYMENT-TYPE", ordered(
                        "C", "GET-PAYMENTS.PAYMENT.CARD-DATA",
                        "S", "GET-PAYMENTS.PAYMENT.SPLIT-DATA"))))
                .on("GET-ORDER-TAXES.ORDER-LINE.LINE-TAX", table(TableMode.COUNT,
                    "GET-ORDER-TAXES.ORDER-LINE.TAX-COUNT")).map()),
            new Case("ORDERS.cpy: defaults", "ORDERS.cpy", mappingOptions("ORDERS.cpy"),
                Map.of()),
            new Case("ORDERS.cpy: unused slots of spaces", "ORDERS.cpy",
                options(Map.of(), Map.of("TOP-PRODUCT", "SPACES"), Map.of()), new Decisions()
                .on("LIST-ORDERS.TOP-PRODUCT", table(TableMode.SPACES, null)).map()),
            new Case("ORDERS.cpy: an empty key", "ORDERS.cpy",
                options(Map.of(), Map.of(), Map.of("TOP-PRODUCT", "PRODUCT-CODE")),
                new Decisions().on("LIST-ORDERS.TOP-PRODUCT", table(TableMode.KEY,
                    "LIST-ORDERS.TOP-PRODUCT.PRODUCT-CODE")).map()),
            new Case("PACKED-DATES.cpy: packed dates and times", "PACKED-DATES.cpy",
                mappingOptions("PACKED-DATES.cpy").withHints(packedDates(), Map.of(),
                    Map.of()), new Decisions()
                .on("GET-ORDER-DATES.ORDER-DATE", date(JsonType.DATE, "yyyyMMdd"))
                .on("GET-ORDER-DATES.SHIP-DATE", date(JsonType.DATE, "yyyyDDD"))
                .on("GET-ORDER-DATES.SHIP-TIME", date(JsonType.TIME, "'0'HHmmss"))
                .on("GET-ORDER-DATES.LAST-UPDATE", date(JsonType.DATE_TIME, "yyyyMMddHHmmss"))
                .map()),
            new Case("SHIPMENT.cpy: a copy member", "SHIPMENT.cpy",
                mappingOptions("SHIPMENT.cpy"), Map.of()),
            new Case("STOCK.cpy: SYNC, INDEX, COPY REPLACING, and THRU", "STOCK.cpy",
                mappingOptions("STOCK.cpy"), Map.of()),
            new Case("TRACKING.cpy: defaults", "TRACKING.cpy", mappingOptions("TRACKING.cpy"),
                Map.of()),
            new Case("TRACKING.cpy: a stripped prefix as Java names", "TRACKING.cpy",
                new MappingOptions(List.of("TRK-"), MappingOptions.INPUT_MARKERS,
                    MappingOptions.OUTPUT_MARKERS, Map.of(), Map.of()), new Decisions()
                .on("GET-TRACKING.TRK-SHIPMENT-ID", javaName("shipmentId"))
                .on("GET-TRACKING.TRK-STATUS", javaName("status"))
                .on("GET-TRACKING.TRK-EVENT-COUNT", javaName("eventCount"))
                .on("GET-TRACKING.TRK-WEIGHT-GRAMS", javaName("weightGrams"))
                .on("GET-TRACKING.TRK-CARRIER", javaName("carrier"))
                .on("GET-TRACKING.TRK-CHARGE", javaName("charge"))
                .on("GET-TRACKING.TRK-EVENT-IDX", javaName("eventIdx")).map()));
    }

    static Stream<NotExpressible> notExpressible() {
        return Stream.of(
            new NotExpressible("LIST-SHIPMENTS.cpy: Continuable and Validatable handlers",
                "LIST-SHIPMENTS.cpy", mappingOptions("LIST-SHIPMENTS.cpy").withHints(
                    Map.of(), Map.of("LIST-SHIPMENTS", "com.example.handlers.ShipmentPaging"),
                    Map.of("LIST-SHIPMENTS", "ShipmentChecks")),
                "a handler class is Java code. The runtime reads the continuation and the"
                    + " validation from the spec, so the spec has no handler classes."),
            new NotExpressible("INVOICE-PARTY.cpy: condition modes", "INVOICE-PARTY.cpy",
                new MappingOptions(List.of(), MappingOptions.INPUT_MARKERS,
                    MappingOptions.OUTPUT_MARKERS, Map.of(), Map.of(), Map.of(), null,
                    Map.of(), Map.of(), Map.of(), Map.of("PARTY-STATUS", ConditionMode.ENUM),
                    Map.of(), Map.of()),
                "the spec keeps the level 88 items, but not the Java form (enum or"
                    + " constants) of them."),
            new NotExpressible("LIST-SHIPMENTS.cpy: a partial table", "LIST-SHIPMENTS.cpy",
                new MappingOptions(List.of(), MappingOptions.INPUT_MARKERS,
                    MappingOptions.OUTPUT_MARKERS, Map.of(), Map.of(), Map.of(), null,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of("SHIPMENT-ENTRY")),
                "the spec has no partial table."),
            new NotExpressible("INVOICE.cpy: a view", "INVOICE.cpy",
                new MappingOptions(List.of(), MappingOptions.INPUT_MARKERS,
                    MappingOptions.OUTPUT_MARKERS, Map.of(), Map.of(), Map.of(), null,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                    List.of("INVOICE-REQUEST")),
                "a variant of the spec selects one alternative. The spec has no view of all"
                    + " alternatives."));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aSpecGivesTheClassesOfItsCopybook(Case parity) {
        Copybooks.Result fromCopybook = Copybooks.generate(copybook(parity.copybook()),
            parseOptions(), parity.options(), PLAIN);
        assertNoErrors(fromCopybook);

        List<JavaFile> fromSpec = generateFromSpecs(parity.copybook(), parity.decisions());

        assertThat(sources(fromSpec)).isNotEmpty().isEqualTo(sources(fromCopybook.files()));
    }

    @ParameterizedTest
    @MethodSource("notExpressible")
    void optionsThatASpecCannotExpressGiveOtherClasses(NotExpressible other) {
        Copybooks.Result fromCopybook = Copybooks.generate(copybook(other.copybook()),
            parseOptions(), other.options(), PLAIN);
        assertNoErrors(fromCopybook);
        List<JavaFile> fromSpec = generateFromSpecs(other.copybook(),
            markerVariant(other.copybook()));

        assertThat(sources(fromSpec)).as(other.reason())
            .isNotEqualTo(sources(fromCopybook.files()));
    }

    @Test
    void eachTestCopybookHasAParityCase() throws IOException {
        Set<String> names;
        try (Stream<Path> files = Files.list(copybook("ACCOUNT.cpy").getParent())) {
            names = files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(".cpy"))
                .collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> covered = cases().map(Case::copybook)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(covered).isEqualTo(names);
    }

    @Test
    void theStockSpecKeepsTheSlackFillerAndTheIndexItem() {
        Copybook parsed = CopybookParser.parse(copybook("STOCK.cpy"), parseOptions())
            .copybook();
        SpecNode root = MappingSpecs.draft(parsed, "GET-STOCK", DraftOptions.defaults()).root();
        assertThat(root.children()).extracting(SpecNode::cobolName).containsExactly(
            "STOCK-ID", "STOCK-LEVEL", "FILLER", "STOCK-QUANTITY", "STORE-ADDRESS",
            "STOCK-IDX");
        assertThat(root.children().get(1).conditions()).hasSize(2);
    }

    @Test
    void specClassesWithTablesAndSubtypesRoundTrip() {
        Compiled classes = Compiled.compile(generateFromSpecs("ORDER-TAXES.cpy",
            caseOf("ORDER-TAXES.cpy").decisions()));

        BookCodec<Object> orders = classes.codec(PACKAGE + ".GetOrderTaxes");
        Object order = orders.newInstance();
        set(order, "orderId", "ORD0000001");
        Object tax = classes.create(PACKAGE + ".LineTax");
        set(tax, "taxCode", "VAT1");
        set(tax, "taxAmount", new BigDecimal("2.30"));
        Object line = classes.create(PACKAGE + ".OrderLine");
        set(line, "productCode", "PRD00001");
        set(line, "quantity", 2);
        set(line, "lineTax", List.of(tax));
        set(order, "orderLine", List.of(line));
        set(order, "orderTotal", new BigDecimal("12.30"));
        Object decodedOrder = orders.newInstance();
        orders.decodeResponse(orders.encodeResponse(order, LATIN), decodedOrder, LATIN);
        Object decodedLine = ((List<?>) get(decodedOrder, "orderLine")).get(0);
        assertThat(get(decodedLine, "taxCount")).isEqualTo(1);
        assertThat(get(((List<?>) get(decodedLine, "lineTax")).get(0), "taxAmount"))
            .isEqualTo(new BigDecimal("2.30"));

        BookCodec<Object> payments = classes.codec(PACKAGE + ".GetPayments");
        Object part = classes.create(PACKAGE + ".SplitPart");
        set(part, "partAmount", 12);
        Object split = classes.create(PACKAGE + ".SplitData");
        set(split, "splitPart", List.of(part));
        Object list = payments.newInstance();
        set(list, "payment", List.of(split));
        Object decoded = payments.newInstance();
        payments.decodeResponse(payments.encodeResponse(list, LATIN), decoded, LATIN);
        Object payment = ((List<?>) get(decoded, "payment")).get(0);
        assertThat(payment.getClass().getSimpleName()).isEqualTo("SplitData");
        assertThat(get(payment, "paymentType")).isEqualTo("S");
    }

    @Test
    void specClassesWithANamedSubtypeRoundTrip() {
        Compiled classes = Compiled.compile(generateFromSpecs("ACCOUNT.cpy",
            caseOf("ACCOUNT.cpy with account-config.json").decisions()));
        BookCodec<Object> codec = classes.codec(PACKAGE + ".GetAccount");
        Object savings = classes.create(PACKAGE + ".SavingsAccount");
        set(savings, "accountId", "ACC-00000001");
        set(savings, "interestRate", new BigDecimal("0.0325"));

        Object result = codec.readResponse(codec.encodeResponse(savings, LATIN),
            codec.newInstance(), LATIN);

        assertThat(result.getClass().getSimpleName()).isEqualTo("SavingsAccount");
        assertThat(get(result, "accountType")).isEqualTo(2);
        assertThat(get(result, "interestRate")).isEqualTo(new BigDecimal("0.0325"));
    }

    // ---- the specs ----

    /** Drafts the spec of each record, applies the decisions, and generates each spec. */
    private static List<JavaFile> generateFromSpecs(String name,
            Map<String, UnaryOperator<SpecNode>> decisions) {
        Copybook parsed = CopybookParser.parse(copybook(name), parseOptions()).copybook();
        Map<String, UnaryOperator<SpecNode>> unused = new LinkedHashMap<>(decisions);
        List<JavaFile> files = new ArrayList<>();
        for (DataItem record : parsed.records()) {
            MappingSpec draft = MappingSpecs.draft(parsed, record.name(),
                DraftOptions.defaults());
            MappingSpec spec = new MappingSpec(draft.formatVersion(), draft.sourceName(),
                draft.sourceHash(), draft.charset(), draft.byteOrder(),
                apply(draft.root(), decisions, unused));
            Copybooks.Result result = Copybooks.generate(spec, PLAIN);
            assertNoErrors(result);
            files.addAll(result.files());
        }
        assertThat(unused).as("decisions on paths that are not in the specs").isEmpty();
        return files;
    }

    private static SpecNode apply(SpecNode node, Map<String, UnaryOperator<SpecNode>> decisions,
                                  Map<String, UnaryOperator<SpecNode>> unused) {
        List<SpecNode> children = node.children().stream()
            .map(child -> apply(child, decisions, unused)).toList();
        SpecNode rebuilt = copy(node, node.jsonType(), node.format(), node.table(),
            node.variant(), node.javaName(), children);
        unused.remove(node.path());
        return decisions.getOrDefault(node.path(), UnaryOperator.identity()).apply(rebuilt);
    }

    private static SpecNode copy(SpecNode n, JsonType jsonType, String format, TableSpec table,
                                 VariantSpec variant, String javaName, List<SpecNode> children) {
        return new SpecNode(n.cobolName(), n.level(), n.path(), n.picture(), n.usage(),
            n.sign(), n.occurs(), n.redefines(), n.value(), n.conditions(), n.location(),
            n.declaration(), n.blankWhenZero(), n.edited(), n.jsonName(), n.direction(),
            jsonType, format, table, n.counter(), variant, javaName, children);
    }

    private static UnaryOperator<SpecNode> date(JsonType type, String format) {
        return n -> copy(n, type, format, n.table(), n.variant(), n.javaName(), n.children());
    }

    private static UnaryOperator<SpecNode> table(TableMode mode, String path) {
        return n -> copy(n, n.jsonType(), n.format(), new TableSpec(mode, path), n.variant(),
            n.javaName(), n.children());
    }

    private static UnaryOperator<SpecNode> variant(VariantSpec variant) {
        return n -> copy(n, n.jsonType(), n.format(), n.table(), variant, n.javaName(),
            n.children());
    }

    private static UnaryOperator<SpecNode> javaName(String name) {
        return n -> copy(n, n.jsonType(), n.format(), n.table(), n.variant(), name,
            n.children());
    }

    /** The spec decisions of one case, by path. */
    private static final class Decisions {

        private final Map<String, UnaryOperator<SpecNode>> byPath = new LinkedHashMap<>();

        Decisions on(String path, UnaryOperator<SpecNode> decision) {
            byPath.merge(path, decision, (a, b) -> n -> b.apply(a.apply(n)));
            return this;
        }

        Map<String, UnaryOperator<SpecNode>> map() {
            return byPath;
        }
    }

    // ---- the options and the variants ----

    private static VariantSpec account() {
        return new VariantSpec.Discriminator("GET-ACCOUNT.ACCOUNT-TYPE", ordered(
            "01", "GET-ACCOUNT.CHECKING-DATA", "02", "GET-ACCOUNT.SAVINGS-DATA"));
    }

    private static VariantSpec invoice() {
        return new VariantSpec.FixedSelection("GET-INVOICE.INVOICE-REQUEST",
            "GET-INVOICE.INVOICE-RESPONSE");
    }

    /** The variant that is equal to the default name markers, by spec path. */
    private static Map<String, UnaryOperator<SpecNode>> markerVariant(String name) {
        return switch (name) {
            case "INVOICE.cpy" -> Map.of("GET-INVOICE.INVOICE-REQUEST", variant(invoice()));
            case "LIST-SHIPMENTS.cpy" -> Map.of("LIST-SHIPMENTS.SHIPMENT-REQUEST",
                variant(shipments()));
            default -> Map.of();
        };
    }

    private static VariantSpec shipments() {
        return new VariantSpec.FixedSelection("LIST-SHIPMENTS.SHIPMENT-REQUEST",
            "LIST-SHIPMENTS.SHIPMENT-RESPONSE");
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, String> shipmentDates() {
        return ordered("*-DATE", "yyyyMMdd", "SHIP-TIME", "HH:mm:ss", "LAST-UPDATE",
            "yyyyMMddHHmmss");
    }

    private static Map<String, String> packedDates() {
        return ordered("ORDER-DATE", "yyyyMMdd", "SHIP-DATE", "yyyyDDD", "SHIP-TIME",
            "'0'HHmmss", "LAST-UPDATE", "yyyyMMddHHmmss");
    }

    private static MappingOptions options(Map<String, String> countFields,
                                          Map<String, String> emptyItems,
                                          Map<String, String> emptyKeys) {
        return new MappingOptions(List.of(), MappingOptions.INPUT_MARKERS,
            MappingOptions.OUTPUT_MARKERS, Map.of(), Map.of(), countFields, null, Map.of(),
            Map.of(), Map.of(), Map.of(), emptyItems, emptyKeys);
    }

    private static MappingOptions config(String name) {
        Path file = copybook(name);
        try {
            return GeneratorConfigJson.read(Files.readString(file), file.getParent())
                .mapping();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Case caseOf(String label) {
        return cases().filter(c -> c.label().startsWith(label)).findFirst().orElseThrow();
    }

    // ---- the checks ----

    private static void assertNoErrors(Copybooks.Result result) {
        assertThat(result.diagnostics()).as(result.diagnostics().toString())
            .extracting(Diagnostic::severity).doesNotContain(Severity.ERROR);
    }

    private static List<String> sources(List<JavaFile> files) {
        return files.stream()
            .sorted(Comparator.comparing(file -> file.packageName() + "." + file.typeSpec().name()))
            .map(JavaFile::toString).toList();
    }
}
