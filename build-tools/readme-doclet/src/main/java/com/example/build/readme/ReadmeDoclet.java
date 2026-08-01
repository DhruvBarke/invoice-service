package com.example.build.readme;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.util.DocTrees;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/**
 * Generates {@code README.md} from {@code package-info.java} comments.
 *
 * <p><b>Why a doclet rather than hand-written markdown.</b> A README in a separate file drifts from
 * the code within about two sprints, and nothing fails when it does. Documentation written as
 * package Javadoc sits beside the thing it describes, is reviewed in the same diff, and — because
 * this doclet runs in the build — regenerates on every compile. If a package is deleted its
 * documentation disappears with it.
 *
 * <p><b>Conventions this doclet reads.</b> The full body of a package comment becomes that section's
 * prose. Custom block tags supply structure:
 * <ul>
 *   <li>{@code @readme.module} — marks the package as a module root; its value is the module title</li>
 *   <li>{@code @readme.order} — integer sort key, so sections appear in reading order rather than
 *       alphabetically</li>
 *   <li>{@code @readme.section} — a heading for this package's section</li>
 *   <li>{@code @readme.depends} — a dependency line, repeated as needed</li>
 * </ul>
 *
 * <p><b>Invocation.</b> Bound to the {@code package} phase via maven-javadoc-plugin under the
 * {@code readme} profile. See the parent pom.
 */
public final class ReadmeDoclet implements Doclet {

    private Reporter reporter;
    private String outputPath = "README.md";
    private String rootTitle = "Module";

    @Override
    public void init(Locale locale, Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return "Readme";
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_21;
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Set.of(
                new SimpleOption("-readmeOutput", 1, "Target README.md path",
                        args -> outputPath = args.get(0)),
                new SimpleOption("-readmeTitle", 1, "Document title",
                        args -> rootTitle = args.get(0)),
                // Javadoc always passes these; accepting and ignoring them keeps the build quiet.
                new SimpleOption("-d", 1, "ignored", args -> { }),
                new SimpleOption("-doctitle", 1, "ignored", args -> { }),
                new SimpleOption("-windowtitle", 1, "ignored", args -> { }),
                new SimpleOption("-notimestamp", 0, "ignored", args -> { }));
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        DocTrees trees = environment.getDocTrees();
        Map<Integer, List<Section>> ordered = new LinkedHashMap<>();
        Section moduleRoot = null;

        for (Element element : environment.getIncludedElements()) {
            if (!(element instanceof PackageElement pkg)) {
                continue;
            }
            DocCommentTree comment = trees.getDocCommentTree(pkg);
            if (comment == null) {
                continue;   // undocumented package: nothing to contribute
            }
            Section section = Section.from(pkg, comment);
            if (section.isModuleRoot()) {
                moduleRoot = section;
            } else {
                ordered.computeIfAbsent(section.order(), k -> new ArrayList<>()).add(section);
            }
        }

        try {
            write(moduleRoot, ordered);
            reporter.print(javax.tools.Diagnostic.Kind.NOTE, "Wrote " + outputPath);
            return true;
        } catch (IOException e) {
            reporter.print(javax.tools.Diagnostic.Kind.ERROR, "README generation failed: " + e);
            return false;
        }
    }

    private void write(Section root, Map<Integer, List<Section>> sections) throws IOException {
        Path target = Path.of(outputPath);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(target))) {
            out.println("<!-- GENERATED from package-info.java. Do not edit by hand. -->");
            out.println("<!-- Regenerate with: mvn -P readme package -->");
            out.println();
            out.println("# " + (root != null ? root.title() : rootTitle));
            out.println();

            if (root != null) {
                out.println(root.body());
                out.println();
                if (!root.dependencies().isEmpty()) {
                    out.println("## Dependencies");
                    out.println();
                    for (String dependency : root.dependencies()) {
                        out.println("- " + dependency);
                    }
                    out.println();
                }
            }

            sections.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .flatMap(e -> e.getValue().stream())
                    .forEach(section -> {
                        out.println("## " + section.heading());
                        out.println();
                        out.println("`" + section.packageName() + "`");
                        out.println();
                        out.println(section.body());
                        out.println();
                    });
        }
    }

    /** Minimal {@link Option} that consumes a fixed argument count and hands them to a consumer. */
    private record SimpleOption(String name, int argCount, String description,
                                java.util.function.Consumer<List<String>> action) implements Option {

        @Override
        public int getArgumentCount() {
            return argCount;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Kind getKind() {
            return Kind.STANDARD;
        }

        @Override
        public List<String> getNames() {
            return List.of(name);
        }

        @Override
        public String getParameters() {
            return argCount == 0 ? "" : "<value>";
        }

        @Override
        public boolean process(String option, List<String> arguments) {
            action.accept(arguments);
            return true;
        }
    }
}
