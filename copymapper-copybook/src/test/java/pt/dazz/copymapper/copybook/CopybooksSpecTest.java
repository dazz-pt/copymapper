package pt.dazz.copymapper.copybook;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.dazz.copymapper.copybook.ast.Copybook;
import pt.dazz.copymapper.copybook.generate.GenerateOptions;
import pt.dazz.copymapper.copybook.map.MappingOptions;
import pt.dazz.copymapper.copybook.parse.CopybookParser;
import pt.dazz.copymapper.copybook.parse.ParseOptions;
import pt.dazz.copymapper.copybook.parse.SourceFormat;
import pt.dazz.copymapper.copybook.spec.DraftOptions;
import pt.dazz.copymapper.copybook.spec.MappingSpec;
import pt.dazz.copymapper.copybook.spec.MappingSpecs;
import pt.dazz.copymapper.copybook.spec.SpecNode;

class CopybooksSpecTest {

    private static final String SOURCE = "01 R.\n   05 A PIC X(4).\n   05 B PIC 9(3).";
    private static final ParseOptions PARSE =
        new ParseOptions(SourceFormat.FREE, StandardCharsets.UTF_8, List.of(), null);
    private static final GenerateOptions GENERATE = new GenerateOptions("com.example", false);

    private static MappingSpec draft() {
        Copybook copybook = CopybookParser.parse("R.cpy", SOURCE, PARSE).copybook();
        return MappingSpecs.draft(copybook, "R", DraftOptions.defaults());
    }

    private static MappingSpec withBadName(MappingSpec spec) {
        return withJavaName(spec, "not a name");
    }

    private static MappingSpec withJavaName(MappingSpec spec, String name) {
        SpecNode root = spec.root();
        SpecNode first = root.children().get(0);
        SpecNode bad = new SpecNode(first.cobolName(), first.level(), first.path(),
            first.picture(), first.usage(), first.sign(), first.occurs(), first.redefines(),
            first.value(), first.conditions(), first.location(), first.declaration(),
            first.blankWhenZero(), first.edited(), first.jsonName(), first.direction(),
            first.jsonType(), first.format(), first.table(), first.counter(),
            first.variant(), name, first.children());
        SpecNode newRoot = new SpecNode(root.cobolName(), root.level(), root.path(),
            root.picture(), root.usage(), root.sign(), root.occurs(), root.redefines(),
            root.value(), root.conditions(), root.location(), root.declaration(),
            root.blankWhenZero(), root.edited(), root.jsonName(), root.direction(),
            root.jsonType(), root.format(), root.table(), root.counter(), root.variant(),
            root.javaName(), List.of(bad, root.children().get(1)));
        return new MappingSpec(spec.formatVersion(), spec.sourceName(), spec.sourceHash(),
            spec.charset(), spec.byteOrder(), newRoot);
    }

    @TempDir
    Path dir;

    @Test
    void aSpecGivesTheSameClassesAsTheCopybook() throws IOException {
        Path file = dir.resolve("R.cpy");
        Files.writeString(file, SOURCE);
        Copybooks.Result fromCopybook = Copybooks.generate(file, PARSE, MappingOptions.defaults(),
            GENERATE);
        Copybooks.Result fromSpec = Copybooks.generate(draft(), GENERATE);
        assertThat(fromSpec.hasErrors()).isFalse();
        assertThat(fromSpec.files()).isNotEmpty().extracting(JavaFile::toString)
            .isEqualTo(fromCopybook.files().stream().map(JavaFile::toString).toList());
    }

    @Test
    void toModelGivesTheModelWithoutFiles() {
        Copybooks.Result result = Copybooks.toModel(draft());
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.model()).isNotNull();
        assertThat(result.files()).isEmpty();
    }

    @Test
    void aTranslationErrorGivesNoModelAndNoFiles() {
        MappingSpec spec = withBadName(draft());
        for (Copybooks.Result result : List.of(Copybooks.toModel(spec),
            Copybooks.generate(spec, GENERATE))) {
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.model()).isNull();
            assertThat(result.files()).isEmpty();
            assertThat(result.diagnostics().get(0).format()).contains("R.cpy:", "error:");
        }
    }

    @Test
    void javaNamesOfTheSpecReachTheClasses() {
        MappingSpec spec = withJavaName(draft(), "renamedField");
        Copybooks.Result result = Copybooks.generate(spec, GENERATE);
        assertThat(result.hasErrors()).as(result.diagnostics().toString()).isFalse();
        assertThat(result.files().get(0).toString()).contains("getRenamedField");
    }
}
