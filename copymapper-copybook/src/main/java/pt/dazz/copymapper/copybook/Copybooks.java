package pt.dazz.copymapper.copybook;

import com.palantir.javapoet.JavaFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pt.dazz.copymapper.copybook.diag.Diagnostic;
import pt.dazz.copymapper.copybook.diag.Severity;
import pt.dazz.copymapper.copybook.generate.GenerateOptions;
import pt.dazz.copymapper.copybook.generate.JavaClassGenerator;
import pt.dazz.copymapper.copybook.map.MapResult;
import pt.dazz.copymapper.copybook.map.MappingOptions;
import pt.dazz.copymapper.copybook.map.ModelMapper;
import pt.dazz.copymapper.copybook.model.BookModel;
import pt.dazz.copymapper.copybook.parse.CopybookParser;
import pt.dazz.copymapper.copybook.parse.ParseOptions;
import pt.dazz.copymapper.copybook.parse.ParseResult;
import pt.dazz.copymapper.copybook.spec.MappingSpec;
import pt.dazz.copymapper.copybook.spec.SpecTranslator;
import pt.dazz.copymapper.copybook.spec.Translation;

/**
 * Runs the copybook pipeline: parse, map, and generate. This is the entry point for the
 * command line and the build plugins.
 */
public final class Copybooks {

    private Copybooks() {
    }

    /**
     * Gives the result of a pipeline run.
     *
     * @param model the model, or {@code null} if an error exists
     * @param files the Java files; empty if an error exists or the run stops at the model
     * @param diagnostics all diagnostics
     */
    public record Result(BookModel model, List<JavaFile> files, List<Diagnostic> diagnostics) {

        /**
         * Creates a result.
         *
         * @param model the model
         * @param files the files
         * @param diagnostics the diagnostics
         */
        public Result {
            files = List.copyOf(files);
            diagnostics = List.copyOf(diagnostics);
        }

        /**
         * Tells if an error exists.
         *
         * @return {@code true} if a diagnostic is an error
         */
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
        }
    }

    /**
     * Parses and maps a copybook.
     *
     * @param copybook the copybook file
     * @param parse the parse options
     * @param mapping the mapping options
     * @return the result, without Java files
     */
    public static Result toModel(Path copybook, ParseOptions parse, MappingOptions mapping) {
        ParseResult parsed = CopybookParser.parse(copybook, parse);
        if (parsed.hasErrors()) {
            return new Result(null, List.of(), parsed.diagnostics());
        }
        MapResult mapped = ModelMapper.map(parsed.copybook(), mapping);
        List<Diagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        diagnostics.addAll(mapped.diagnostics());
        return new Result(mapped.model(), List.of(), diagnostics);
    }

    /**
     * Parses, maps, and generates a copybook.
     *
     * @param copybook the copybook file
     * @param parse the parse options
     * @param mapping the mapping options
     * @param generate the generate options
     * @return the result
     */
    public static Result generate(Path copybook, ParseOptions parse, MappingOptions mapping,
                                  GenerateOptions generate) {
        Result model = toModel(copybook, parse, mapping);
        if (model.hasErrors()) {
            return model;
        }
        return new Result(model.model(), JavaClassGenerator.generate(model.model(), generate),
            model.diagnostics());
    }

    /**
     * Generates the Java classes of a model.
     *
     * @param model the model, for example from a model JSON file
     * @param generate the generate options
     * @return the result
     */
    public static Result generate(BookModel model, GenerateOptions generate) {
        return new Result(model, JavaClassGenerator.generate(model, generate), List.of());
    }

    /**
     * Translates and maps a mapping spec.
     *
     * @param spec the mapping spec
     * @return the result, without Java files
     */
    public static Result toModel(MappingSpec spec) {
        return toModel(spec, null);
    }

    /**
     * Translates and maps a mapping spec, with a check of the record length. An error in the
     * translation stops the run.
     *
     * @param spec the mapping spec
     * @param maxRecordLength the maximum record length in bytes, or {@code null} for no check.
     *     A longer record gives a warning.
     * @return the result, without Java files
     */
    public static Result toModel(MappingSpec spec, Integer maxRecordLength) {
        Translation translation = SpecTranslator.translate(spec);
        if (translation.hasErrors()) {
            return new Result(null, List.of(), translation.diagnostics());
        }
        MappingOptions o = translation.options();
        MappingOptions options = new MappingOptions(o.stripPrefixes(), o.inputMarkers(),
            o.outputMarkers(), o.directions(), o.subTypes(), o.countFields(), maxRecordLength,
            o.dates(), o.continuable(), o.validatable(), o.conditions(), o.emptyItems(),
            o.emptyKeys(), o.partialTables(), o.views());
        MapResult mapped = ModelMapper.map(translation.copybook(), options,
            translation.javaNames());
        List<Diagnostic> diagnostics = new ArrayList<>(translation.diagnostics());
        diagnostics.addAll(mapped.diagnostics());
        return new Result(mapped.model(), List.of(), diagnostics);
    }

    /**
     * Translates, maps, and generates a mapping spec.
     *
     * @param spec the mapping spec
     * @param generate the generate options
     * @return the result
     */
    public static Result generate(MappingSpec spec, GenerateOptions generate) {
        return generate(spec, null, generate);
    }

    /**
     * Translates, maps, and generates a mapping spec, with a check of the record length.
     *
     * @param spec the mapping spec
     * @param maxRecordLength the maximum record length in bytes, or {@code null} for no check.
     *     A longer record gives a warning.
     * @param generate the generate options
     * @return the result
     */
    public static Result generate(MappingSpec spec, Integer maxRecordLength,
                                  GenerateOptions generate) {
        Result model = toModel(spec, maxRecordLength);
        if (model.hasErrors()) {
            return model;
        }
        return new Result(model.model(), JavaClassGenerator.generate(model.model(), generate),
            model.diagnostics());
    }
}
