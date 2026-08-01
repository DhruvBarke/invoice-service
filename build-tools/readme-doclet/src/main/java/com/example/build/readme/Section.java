package com.example.build.readme;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.PackageElement;

/**
 * One package's contribution to a README.
 *
 * <p>Parsing is intentionally forgiving: a package with no custom tags still produces a section from
 * its prose, so documentation is never lost because someone forgot a tag. Only ordering and grouping
 * depend on the tags.
 */
record Section(
        String packageName,
        String title,
        String heading,
        String body,
        int order,
        boolean isModuleRoot,
        List<String> dependencies
) {
    private static final int DEFAULT_ORDER = 500;

    static Section from(PackageElement pkg, DocCommentTree comment) {
        String packageName = pkg.getQualifiedName().toString();
        String body = renderHtmlAsMarkdown(join(comment.getFullBody()));

        String moduleTitle = null;
        String heading = simpleName(packageName);
        int order = DEFAULT_ORDER;
        List<String> dependencies = new ArrayList<>();

        for (DocTree tag : comment.getBlockTags()) {
            if (!(tag instanceof UnknownBlockTagTree unknown)) {
                continue;
            }
            String value = join(unknown.getContent()).trim();
            switch (unknown.getTagName()) {
                case "readme.module" -> moduleTitle = value;
                case "readme.section" -> heading = value;
                case "readme.order" -> order = parseOrder(value, order);
                case "readme.depends" -> dependencies.add(value);
                default -> { }   // unknown tag: ignore rather than fail the build
            }
        }

        return new Section(packageName,
                moduleTitle != null ? moduleTitle : heading,
                heading, body, order, moduleTitle != null, List.copyOf(dependencies));
    }

    private static int parseOrder(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String join(List<? extends DocTree> trees) {
        StringBuilder sb = new StringBuilder();
        for (DocTree tree : trees) {
            sb.append(tree.toString());
        }
        return sb.toString();
    }

    private static String simpleName(String packageName) {
        int last = packageName.lastIndexOf('.');
        return last < 0 ? packageName : packageName.substring(last + 1);
    }

    /**
     * Translates the small subset of HTML that Javadoc encourages into Markdown. Deliberately
     * limited — the point is readable prose in both renderings, not a general converter. Anything
     * outside this subset (tables, pre blocks) passes through as raw HTML, which renders acceptably
     * on most Git hosts.
     */
    private static String renderHtmlAsMarkdown(String html) {
        return html
                .replaceAll("(?s)<p>\\s*", "\n\n")
                .replaceAll("(?s)</p>", "")
                .replaceAll("<b>|</b>|<strong>|</strong>", "**")
                .replaceAll("<i>|</i>|<em>|</em>", "_")
                .replaceAll("(?s)<ul>\\s*", "\n")
                .replaceAll("(?s)</ul>", "\n")
                .replaceAll("(?s)<li>\\s*", "- ")
                .replaceAll("(?s)</li>", "")
                .replaceAll("\\{@code ([^}]*)}", "`$1`")
                .replaceAll("\\{@link ([^}\\s]*)[^}]*}", "`$1`")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
