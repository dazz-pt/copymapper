package pt.dazz.copymapper.copybook.tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopybookTreeTest {

    private static final String BASE = "com.example.mainframe";

    @TempDir
    Path work;

    private Path output;
    private Path copylib;
    private final RecordingLog log = new RecordingLog();

    @BeforeEach
    void setUp() {
        output = work.resolve("out");
        copylib = TestCopybooks.copy("copylib", work);
    }

    private CopybookTree.Settings settings(Path sourceDir, List<Path> copyDirs,
                                                 String base, boolean lombok,
                                                 boolean perCopybook) {
        return settings(sourceDir, copyDirs, base, lombok, perCopybook, null);
    }

    private CopybookTree.Settings settings(Path sourceDir, List<Path> copyDirs,
                                                 String base, boolean lombok,
                                                 boolean perCopybook, Integer maxRecordLength) {
        return new CopybookTree.Settings(sourceDir, copyDirs, base, lombok, perCopybook,
            "Set copymapper.copybooks.packageName.",
            "copymapper.copybooks.packagePerCopybook = true", maxRecordLength);
    }

    private void run(String name, List<Path> copyDirs, String base, boolean lombok,
                     boolean perCopybook) throws IOException {
        Path source = TestCopybooks.copy(name, work);
        CopybookTree.run(settings(source, copyDirs, base, lombok, perCopybook),
            TestCopybooks.copybooks(source), output, log);
    }

    @Test
    void generatesIntoDerivedPackages() throws IOException {
        run("tree", List.of(copylib), BASE, false, false);
        assertThat(output.resolve("com/example/mainframe/orders/Order.java")).exists();
        assertThat(output.resolve("com/example/invoicing/Invoice.java")).exists();
        assertThat(output.resolve("com/example/invoicing/Invoice.java")).content()
            .contains("street");
    }

    @Test
    void optionsFileLombokWins() throws IOException {
        run("tree", List.of(copylib), BASE, false, false);
        assertThat(output.resolve("com/example/invoicing/Invoice.java")).content()
            .contains("lombok");
        assertThat(output.resolve("com/example/mainframe/orders/Order.java")).content()
            .doesNotContain("lombok");
    }

    @Test
    void extensionLombokApplies() throws IOException {
        run("tree", List.of(copylib), BASE, true, false);
        assertThat(output.resolve("com/example/mainframe/orders/Order.java")).content()
            .contains("lombok");
    }

    @Test
    void perCopybookAddsASegment() throws IOException {
        run("tree", List.of(copylib), BASE, false, true);
        assertThat(output.resolve("com/example/mainframe/orders/order/Order.java")).exists();
        assertThat(output.resolve("com/example/invoicing/Invoice.java")).exists();
    }

    @Test
    void missingPackageNameFails() {
        Path source = TestCopybooks.copy("tree", work);
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(copylib), null, false, false),
            List.of(source.resolve("orders/ORDER.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("Set copymapper.copybooks.packageName.");
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void missingPackageNameIsNoErrorWhenTheFileSetsIt() throws IOException {
        Path source = TestCopybooks.copy("tree", work);
        CopybookTree.run(settings(source, List.of(copylib), null, false, false),
            List.of(source.resolve("billing/INVOICE.cpy")), output, log);
        assertThat(output.resolve("com/example/invoicing/Invoice.java")).exists();
    }

    @Test
    void noCopybookCleansTheOutput() throws IOException {
        Files.createDirectories(output.resolve("old/pkg"));
        Files.writeString(output.resolve("old/pkg/Stale.java"), "class Stale {}");
        Files.writeString(output.resolve("Top.java"), "class Top {}");
        CopybookTree.run(settings(work, List.of(), BASE, false, false), List.of(), output,
            log);
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void createsAMissingOutputDirectory() throws IOException {
        CopybookTree.run(settings(work, List.of(), BASE, false, false), List.of(), output,
            log);
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void missingCopyMemberFails() {
        Path source = TestCopybooks.copy("tree", work);
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("billing/INVOICE.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).anySatisfy(e -> assertThat(e)
            .contains("copy member 'ADDRESS' not found"));
    }

    @Test
    void reportsErrorsOfAllCopybooks() {
        assertThatThrownBy(() -> run("broken", List.of(), BASE, false, false))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("2 input(s) have errors. See the messages above.");
        assertThat(log.errors).anySatisfy(e -> assertThat(e).contains("BAD.cpy"));
        assertThat(log.errors).anySatisfy(e -> assertThat(e).contains("WORSE.cpy"));
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void warningsGoToWarn() throws IOException {
        run("warning", List.of(), BASE, false, false);
        assertThat(log.warnings).singleElement().asString()
            .contains("PAYMENT-NUMBER redefines PAYMENT-ID and is skipped");
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/Payment.java")).exists();
    }

    @Test
    void infosGoToInfo() throws IOException {
        run("warning", List.of(), BASE, false, false);
        assertThat(log.infos).singleElement().asString()
            .endsWith("info: record PAYMENT has 8 bytes");
    }

    @Test
    void maxRecordLengthOfTheSettingsGivesAWarning() throws IOException {
        Path source = TestCopybooks.copy("warning", work);
        CopybookTree.run(settings(source, List.of(), BASE, false, false, 4),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.warnings).anySatisfy(w -> assertThat(w).contains("record PAYMENT has 8"
            + " bytes, more than the maximum record length of 4 bytes."));
        assertThat(output.resolve("com/example/mainframe/Payment.java")).exists();
    }

    @Test
    void maxRecordLengthOfTheOptionsFileWins() throws IOException {
        Path source = TestCopybooks.copy("warning", work);
        Files.writeString(source.resolve("PAYMENT.json"), "{\"maxRecordLength\": 8}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false, 4),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.warnings).noneSatisfy(w -> assertThat(w).contains("maximum"));
    }

    @Test
    void optionsFileKeepsTheEmptyItemMarkers() throws IOException {
        Path source = Files.createDirectories(work.resolve("markers"));
        Files.writeString(source.resolve("ORDER.cpy"), String.join("\n",
            "       01  ORDER.",
            "           05  ORDER-LINE OCCURS 5 TIMES.",
            "               10  PRODUCT-ID           PIC 9(6).", ""));
        Files.writeString(source.resolve("ORDER.json"), "{\"emptyItems\": {\"ORDER-LINE\":"
            + " \"SPACES\"}, \"emptyKeys\": {\"ORDER-LINE\": \"PRODUCT-ID\"}}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/Order.java")).content()
            .contains("emptyKey = \"productId\"", "emptyItem = EmptyItem.SPACES");
    }

    @Test
    void optionsFileKeepsTheViews() throws IOException {
        Path source = Files.createDirectories(work.resolve("views"));
        Files.writeString(source.resolve("ORDER.cpy"), String.join("\n",
            "       01  ORDER.",
            "           05  ORDER-CODE               PIC X(4).",
            "           05  ORDER-NUMBER REDEFINES ORDER-CODE PIC 9(4).", ""));
        Files.writeString(source.resolve("ORDER.json"), "{\"views\": [\"ORDER-CODE\"]}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/Order.java")).content()
            .contains("redefines = \"orderCode\"", "Integer orderNumber");
    }

    @Test
    void optionsFileKeepsThePartialTables() throws IOException {
        Path source = Files.createDirectories(work.resolve("partial"));
        Files.writeString(source.resolve("ORDER.cpy"), String.join("\n",
            "       01  ORDER.",
            "           05  ORDER-LINE OCCURS 5 TIMES.",
            "               10  PRODUCT-ID           PIC 9(6).", ""));
        Files.writeString(source.resolve("ORDER.json"), "{\"partialTables\": [\"ORDER-LINE\"]}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/Order.java")).content()
            .contains("partial = true");
    }

    @Test
    void optionsFileKeepsTheDateHints() throws IOException {
        Path source = Files.createDirectories(work.resolve("dates"));
        Files.writeString(source.resolve("ORDER.cpy"),
            "       01  ORDER.\n           05  ORDER-DATE               PIC 9(8).\n");
        Files.writeString(source.resolve("ORDER.json"),
            "{\"dates\": {\"ORDER-DATE\": \"yyyyMMdd\"}}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            TestCopybooks.copybooks(source), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/Order.java")).content()
            .contains("LocalDate orderDate");
    }

    @Test
    void maxRecordLengthMustBePositive() {
        Path source = TestCopybooks.copy("warning", work);
        assertThatThrownBy(() -> CopybookTree.run(settings(source, List.of(), BASE, false,
            false, 0), TestCopybooks.copybooks(source), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("The maximum record length must be 1 or more, not 0.");
    }

    @Test
    void invalidJsonGivesTheFilePath() {
        assertThatThrownBy(() -> run("badjson", List.of(), BASE, false, false))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).singleElement().asString()
            .startsWith(work.resolve("badjson/SHIPMENT.json") + ": ");
    }

    private Path shipment(byte[] options) throws IOException {
        Path source = TestCopybooks.copy("badjson", work);
        Files.write(source.resolve("SHIPMENT.json"), options);
        return source;
    }

    @Test
    void unreadableOptionsFileGivesTheFilePath() throws IOException {
        Path source = shipment(new byte[] {'{', (byte) 0xFF, '}'});
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("SHIPMENT.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).singleElement().asString()
            .startsWith(source.resolve("SHIPMENT.json") + ": cannot read the file: ");
        assertThat(CopybookTree.configCopyDirs(List.of(source.resolve("SHIPMENT.cpy"))))
            .isEmpty();
    }

    @Test
    void invalidCopyDirPathGivesTheFilePath() throws IOException {
        Path source = shipment("{\"copyDirs\": [\"lib\\u0000x\"]}".getBytes());
        assertThat(CopybookTree.configCopyDirs(List.of(source.resolve("SHIPMENT.cpy"))))
            .isEmpty();
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("SHIPMENT.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class);
        assertThat(log.errors).singleElement().asString()
            .startsWith(source.resolve("SHIPMENT.json") + ": a path in copyDirs is not valid: ");
    }

    @Test
    void invalidPackageInTheOptionsFileFails() throws IOException {
        Path source = shipment("{\"package\": \"com.my-co.shipping\"}".getBytes());
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("SHIPMENT.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).containsExactly("SHIPMENT.cpy: the package 'com.my-co.shipping'"
            + " is not a valid Java package name");
    }

    @Test
    void emptyBasePackageFails() {
        Path source = TestCopybooks.copy("tree", work);
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(copylib), "", false, false),
            List.of(source.resolve("orders/ORDER.cpy")), output, log))
            .isInstanceOf(CopybookTreeException.class);
        assertThat(log.errors).containsExactly("orders/ORDER.cpy: the package '.orders'"
            + " is not a valid Java package name");
    }

    @Test
    void collisionFails() {
        assertThatThrownBy(() -> run("collision", List.of(copylib), BASE, false, false))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessageContaining("class com.example.mainframe.OrderLine comes from"
                + " CUSTOMER-ORDER.cpy and CUSTOMER-RETURN.cpy")
            .hasMessageContaining("packagePerCopybook = true");
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void reportsAllCollisions() {
        assertThatThrownBy(() -> run("collision2", List.of(copylib), BASE, false, false))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessageContaining("class com.example.mainframe.OrderLine comes from")
            .hasMessageContaining("class com.example.mainframe.Contact comes from");
    }

    @Test
    void perCopybookRemovesTheCollision() throws IOException {
        run("collision", List.of(copylib), BASE, false, true);
        assertThat(output.resolve("com/example/mainframe/customer_order/OrderLine.java")).exists();
        assertThat(output.resolve("com/example/mainframe/customer_return/OrderLine.java"))
            .exists();
    }

    @Test
    void jsonCopyDirsAreSearchedFirst() throws IOException {
        TestCopybooks.copy("jsoncopylib", work);
        run("jsoncopy", List.of(copylib), BASE, false, false);
        assertThat(output.resolve("com/example/mainframe/Account.java")).content()
            .contains("bankName");
    }

    @Test
    void configCopyDirsReadsTheOptionsFiles() {
        Path account = TestCopybooks.copy("jsoncopy", work);
        Path tree = TestCopybooks.copy("tree", work);
        Path badjson = TestCopybooks.copy("badjson", work);
        assertThat(CopybookTree.configCopyDirs(List.of(account.resolve("ACCOUNT.cpy"))))
            .containsExactly(account.resolve("../jsoncopylib"));
        assertThat(CopybookTree.configCopyDirs(List.of(tree.resolve("orders/ORDER.cpy"),
            badjson.resolve("SHIPMENT.cpy")))).isEmpty();
    }

    @Test
    void customMessagesAreUsed() {
        Path tree = TestCopybooks.copy("tree", work);
        Path collision = TestCopybooks.copy("collision", work);
        String missing = "Set the packageName parameter of copymapper-copybook-maven-plugin.";
        String hint = "<packagePerCopybook>true</packagePerCopybook>";
        assertThatThrownBy(() -> CopybookTree.run(new CopybookTree.Settings(tree, List.of(),
            null, false, false, missing, hint, null), List.of(tree.resolve("orders/ORDER.cpy")),
            output, log)).hasMessage(missing);
        assertThatThrownBy(() -> CopybookTree.run(new CopybookTree.Settings(collision,
            List.of(copylib), BASE, false, false, missing, hint, null),
            TestCopybooks.copybooks(collision), output, log))
            .hasMessageContaining("Set " + hint + ", set \"package\" in an options file");
    }

    @Test
    void relativePathBelowTheBase() {
        assertThat(CopybookTree.relativePath(work, work.resolve("lib/members")))
            .isEqualTo("lib/members");
        assertThat(CopybookTree.relativePath(work.resolve("a"), work.resolve("b")))
            .isEqualTo("../b");
    }

    @Test
    void relativePathWithAnotherRootIsAbsolute() throws IOException {
        try (java.nio.file.FileSystem windows = com.google.common.jimfs.Jimfs.newFileSystem(
            com.google.common.jimfs.Configuration.windows().toBuilder()
                .setRoots("C:\\", "D:\\").build())) {
            Path base = windows.getPath("C:\\work\\model\\src\\main\\copybook");
            Path lib = windows.getPath("D:\\copylib");
            assertThat(CopybookTree.relativePath(base, lib)).isEqualTo("D:/copylib");
            assertThat(CopybookTree.relativePath(base, windows.getPath("C:\\work\\lib")))
                .isEqualTo("../../../../lib");
        }
    }

    @Test
    void optionsFileName() {
        assertThat(CopybookTree.optionsFile(Path.of("a/ORDER.cpy")))
            .isEqualTo(Path.of("a/ORDER.json"));
        assertThat(CopybookTree.optionsFile(Path.of("ORDER")))
            .isEqualTo(Path.of("ORDER.json"));
    }

    /** Gives the copybooks and the mapping specs of a tree, as the plugins find them. */
    private static List<Path> inputs(Path source) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(source)) {
            return files.filter(f -> f.toString().endsWith(".cpy")
                || f.toString().endsWith(".mapping.json")).sorted().toList();
        }
    }

    private Path spec() {
        return TestCopybooks.copy("spec", work);
    }

    @Test
    void specIncludePatternFindsTheSpecs() {
        assertThat(CopybookTree.SPEC_INCLUDE).isEqualTo("**/*.mapping.json");
    }

    @Test
    void isSpecIgnoresTheCase() {
        assertThat(CopybookTree.isSpec(Path.of("a/ORDER.mapping.json"))).isTrue();
        assertThat(CopybookTree.isSpec(Path.of("ORDER.MAPPING.JSON"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of("ORDER.Mapping.json"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of("ORDER.json"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of("ORDER.mapping.cpy"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of("mapping.json"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of(".mapping.json"))).isFalse();
        assertThat(CopybookTree.isSpec(Path.of("A.mapping.json"))).isTrue();
    }

    @Test
    void optionsFileOfASpec() {
        assertThat(CopybookTree.optionsFile(Path.of("a/ORDER.mapping.json")))
            .isEqualTo(Path.of("a/ORDER.json"));
        assertThat(CopybookTree.optionsFile(Path.of("a/ORDER.MAPPING.JSON")))
            .isEqualTo(Path.of("a/ORDER.MAPPING.json"));
    }

    @Test
    void specNextToACopybookReplacesIt() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, false), inputs(source),
            output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/ordering/Order.java")).content()
            .contains("orderId", "05 AMOUNT PIC S9(7)V99 COMP-3.");
    }

    @Test
    void specReplacesACopybookAlsoWhenTheListHasOnlyTheCopybook() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("orders/ORDER.cpy")), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/ordering/Order.java")).exists();
        assertThat(output.resolve("com/example/mainframe/payments/Payment.java"))
            .doesNotExist();
    }

    @Test
    void anExcludedSpecNextToASelectedCopybookIsStillUsed() throws IOException {
        Path source = spec();
        List<Path> selected = inputs(source).stream()
            .filter(file -> !file.endsWith("orders/ORDER.mapping.json")).toList();
        assertThat(selected).contains(source.resolve("orders/ORDER.cpy"));
        CopybookTree.run(settings(source, List.of(), BASE, false, false), selected, output,
            log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/ordering/Order.java")).content()
            .contains("orderId");
    }

    @Test
    void theCopybookThatASpecReplacesHasAnError() throws IOException {
        Path source = spec();
        Path alone = Files.createDirectories(work.resolve("alone"));
        Path order = Files.copy(source.resolve("orders/ORDER.cpy"), alone.resolve("ORDER.cpy"));
        assertThatThrownBy(() -> CopybookTree.run(
            settings(alone, List.of(), BASE, false, false), List.of(order), output, log))
            .isInstanceOf(CopybookTreeException.class);
    }

    @Test
    void specAloneIsAnInput() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("payments/PAYMENT.mapping.json")), output, log);
        assertThat(output.resolve("com/example/mainframe/payments/Payment.java")).content()
            .contains("paymentId");
    }

    @Test
    void specWithPackagePerCopybookUsesTheBaseName() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, true), inputs(source),
            output, log);
        assertThat(output.resolve("com/example/mainframe/payments/payment/Payment.java"))
            .exists();
    }

    @Test
    void maxRecordLengthOfTheSettingsAppliesToASpec() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, false, 4), inputs(source),
            output, log);
        assertThat(log.warnings).contains("payments/PAYMENT.mapping.json: PAYMENT.cpy:1:8:"
            + " warning: record PAYMENT has 8 bytes, more than the maximum record length of 4"
            + " bytes.");
        assertThat(output.resolve("com/example/mainframe/payments/Payment.java")).exists();
    }

    @Test
    void anUpperCaseSpecNameIsNotASpec() throws IOException {
        Path source = Files.createDirectories(work.resolve("upper"));
        Files.writeString(source.resolve("PAY.cpy"),
            "       01  PAY-RECORD.\n           05  PAY-ID               PIC X(4).\n");
        Files.copy(TestCopybooks.copy("spec", work).resolve("payments/PAYMENT.mapping.json"),
            source.resolve("PAY.MAPPING.JSON"));
        CopybookTree.run(settings(source, List.of(), BASE, false, false),
            List.of(source.resolve("PAY.cpy")), output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/PayRecord.java")).exists();
        assertThat(output.resolve("com/example/mainframe/Payment.java")).doesNotExist();
    }

    @Test
    void specDiagnosticsNameTheSpec() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, false, false), inputs(source),
            output, log);
        assertThat(log.warnings).singleElement().asString()
            .startsWith("payments/PAYMENT.mapping.json: PAYMENT.cpy:3:12: warning:")
            .contains("PAYMENT-NUMBER redefines PAYMENT-ID and is skipped");
        assertThat(log.infos).contains("payments/PAYMENT.mapping.json: PAYMENT.cpy:1:8: info:"
            + " record PAYMENT has 8 bytes");
    }

    @Test
    void specOptionsFileSetsThePackageAndLombok() throws IOException {
        Path source = spec();
        Files.writeString(source.resolve("payments/PAYMENT.json"),
            "{\"package\": \"com.example.payments\", \"lombok\": true}");
        CopybookTree.run(settings(source, List.of(), BASE, false, false), inputs(source),
            output, log);
        assertThat(output.resolve("com/example/payments/Payment.java")).content()
            .contains("lombok");
    }

    @Test
    void settingsLombokAppliesToASpec() throws IOException {
        Path source = spec();
        CopybookTree.run(settings(source, List.of(), BASE, true, false), inputs(source),
            output, log);
        assertThat(output.resolve("com/example/mainframe/payments/Payment.java")).content()
            .contains("lombok");
    }

    @Test
    void specOptionsFileWithAMappingKeyFails() throws IOException {
        Path source = spec();
        Files.writeString(source.resolve("orders/ORDER.json"),
            "{\"package\": \"com.example.ordering\", \"stripPrefixes\": [\"WS-\"]}");
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).containsExactly(source.resolve("orders/ORDER.json")
            + ": the options of a mapping spec permit only the keys package and lombok, not"
            + " 'stripPrefixes'");
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void unreadableSpecOptionsFileGivesTheFilePath() throws IOException {
        Path source = spec();
        Files.write(source.resolve("orders/ORDER.json"), new byte[] {'{', (byte) 0xFF, '}'});
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class);
        assertThat(log.errors).singleElement().asString()
            .startsWith(source.resolve("orders/ORDER.json") + ": cannot read the file: ");
    }

    @Test
    void invalidSpecGivesTheFilePath() throws IOException {
        Path source = spec();
        Files.writeString(source.resolve("payments/PAYMENT.mapping.json"), "{\"formatVersion\": 9}");
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).singleElement().asString()
            .startsWith("payments/PAYMENT.mapping.json: ");
    }

    @Test
    void unreadableSpecGivesTheFilePath() throws IOException {
        Path source = spec();
        Files.write(source.resolve("payments/PAYMENT.mapping.json"),
            new byte[] {'{', (byte) 0xFF, '}'});
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class);
        assertThat(log.errors).singleElement().asString()
            .startsWith("payments/PAYMENT.mapping.json: cannot read the file: ");
    }

    @Test
    void specWithATranslationErrorFails() throws IOException {
        Path source = spec();
        Path payment = source.resolve("payments/PAYMENT.mapping.json");
        Files.writeString(payment, Files.readString(payment)
            .replace("\"jsonName\":\"paymentId\"", "\"javaName\":\"class\",\"jsonName\":\"paymentId\""));
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessage("1 input(s) have errors. See the messages above.");
        assertThat(log.errors).singleElement().asString()
            .startsWith("payments/PAYMENT.mapping.json: PAYMENT.cpy:2:12: error: ");
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void specAndCopybookCollide() throws IOException {
        Path source = spec();
        Files.copy(source.resolve("payments/PAYMENT.mapping.json"),
            source.resolve("payments/OTHER.mapping.json"));
        assertThatThrownBy(() -> CopybookTree.run(
            settings(source, List.of(), BASE, false, false), inputs(source), output, log))
            .isInstanceOf(CopybookTreeException.class)
            .hasMessageContaining("class com.example.mainframe.payments.Payment comes from"
                + " payments/OTHER.mapping.json and payments/PAYMENT.mapping.json");
    }

    @Test
    void copybookNamedLikeASpecHasNoOptionsFile() throws IOException {
        Path source = Files.createDirectories(work.resolve("names"));
        Files.writeString(source.resolve("PAY.mapping.cpy"),
            "       01  PAY-RECORD.\n           05  PAY-ID               PIC X(4).\n");
        Files.copy(TestCopybooks.copy("spec", work).resolve("payments/PAYMENT.mapping.json"),
            source.resolve("PAY.mapping.json"));
        CopybookTree.run(settings(source, List.of(), BASE, false, false), inputs(source),
            output, log);
        assertThat(log.errors).isEmpty();
        assertThat(output.resolve("com/example/mainframe/PayRecord.java")).exists();
        assertThat(output.resolve("com/example/mainframe/Payment.java")).exists();
        assertThat(CopybookTree.configCopyDirs(List.of(source.resolve("PAY.mapping.cpy"))))
            .isEmpty();
    }

    @Test
    void configCopyDirsSkipsTheSpecsAndTheCopybooksThatTheyReplace() throws IOException {
        Path source = spec();
        Files.writeString(source.resolve("orders/ORDER.json"), "{\"copyDirs\": [\"lib\"]}");
        Files.writeString(source.resolve("payments/PAYMENT.json"), "{\"copyDirs\": [\"lib\"]}");
        assertThat(CopybookTree.configCopyDirs(inputs(source))).isEmpty();
    }

    private static final class RecordingLog implements CopybookTree.Log {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }
    }
}
