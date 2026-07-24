/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring.data.search;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.spring.AddSpringProperty;
import org.openrewrite.java.spring.table.MongoValueRepresentationFields;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.properties.PropertiesIsoVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.openrewrite.java.spring.data.search.FindMissingMongoValueRepresentation.*;

final class MongoValueRepresentationDiagnostics {

    private MongoValueRepresentationDiagnostics() {
    }

    static SourceFile apply(FindMissingMongoValueRepresentation recipe, SourceFile source, JavaProject project,
                            Accumulator acc, ExecutionContext ctx) {
        if (acc.reportedProjects.contains(project)) {
            return source;
        }
        List<Occurrence> unresolved = unresolvedOccurrences(project, acc);
        if (unresolved.isEmpty()) {
            return source;
        }
        insertRowsOnce(recipe, project, unresolved, acc, ctx);

        Path preferredConfiguration = acc.configurationSources.get(project);
        if (preferredConfiguration == null) {
            return markJavaFallback(source, project, unresolved, acc, ctx);
        }
        if (!(source instanceof Properties.File) && !(source instanceof Yaml.Documents)) {
            return source;
        }

        List<ConfigurationIssue> issues = unresolvedConfigurationIssues(source.getSourcePath(), project, acc);
        boolean preferred = source.getSourcePath().equals(preferredConfiguration);
        boolean missingUuid = hasKind(unresolved, ValueKind.UUID);
        boolean missingBigNumber = hasKind(unresolved, ValueKind.BIG_NUMBER);
        boolean addUuid = preferred && missingUuid && !acc.uuidPropertyPresent.contains(project);
        boolean addBigNumber = preferred && missingBigNumber && !acc.bigNumberPropertyPresent.contains(project);
        if (issues.isEmpty() && !addUuid && !addBigNumber) {
            return source;
        }

        SourceFile changed = source;
        if (addUuid) {
            changed = addCommentedProperty(changed, ValueKind.UUID, ctx);
        }
        if (addBigNumber) {
            changed = addCommentedProperty(changed, ValueKind.BIG_NUMBER, ctx);
        }
        if (!issues.isEmpty()) {
            changed = markConfigurationIssues(changed, issues, ctx);
        }
        return changed.withMarkers(changed.getMarkers().addIfAbsent(new ProjectDiagnostic(Tree.randomId())));
    }

    private static void insertRowsOnce(FindMissingMongoValueRepresentation recipe, JavaProject project,
                                       List<Occurrence> unresolved, Accumulator acc, ExecutionContext ctx) {
        if (!acc.rowsInsertedProjects.add(project)) {
            return;
        }
        for (Occurrence occurrence : unresolved) {
            recipe.affectedFields.insertRow(ctx, new MongoValueRepresentationFields.Row(
                    occurrence.getSourcePath().toString(), occurrence.getOwningType(), occurrence.getField(),
                    occurrence.getKind().displayName, occurrence.getKind().configurationProperty));
        }
    }

    private static SourceFile markJavaFallback(SourceFile source, JavaProject project, List<Occurrence> unresolved,
                                               Accumulator acc, ExecutionContext ctx) {
        Occurrence target = unresolved.stream()
                .min(Comparator.comparing((Occurrence occurrence) -> occurrence.getSourcePath().toString())
                        .thenComparing(Occurrence::getOwningType)
                        .thenComparing(Occurrence::getField)
                        .thenComparing(occurrence -> occurrence.getKind().name()))
                .orElse(null);
        if (target == null || !source.getSourcePath().equals(target.getSourcePath()) ||
                !(source instanceof JavaSourceFile) || !acc.javaFallbackMarkedProjects.add(project)) {
            return source;
        }
        SourceFile marked = markJavaClass((JavaSourceFile) source, target.getOwningClassId(),
                diagnosticMessage(hasKind(unresolved, ValueKind.UUID), hasKind(unresolved, ValueKind.BIG_NUMBER)), ctx);
        return marked.withMarkers(marked.getMarkers().addIfAbsent(new ProjectDiagnostic(Tree.randomId())));
    }

    private static SourceFile addCommentedProperty(SourceFile source, ValueKind kind, ExecutionContext ctx) {
        String path = source.getSourcePath().toString().replace('\\', '/');
        SourceFile withProperty = (SourceFile) new AddSpringProperty(
                kind.configurationProperty, REPRESENTATION_PLACEHOLDER, null, Collections.singletonList(path))
                .getVisitor().visitNonNull(source, ctx);
        if (withProperty instanceof Properties.File) {
            return (SourceFile) new org.openrewrite.properties.AddPropertyComment(
                    kind.configurationProperty, kind.missingPropertyComment, true)
                    .getVisitor().visitNonNull(withProperty, ctx);
        }
        return (SourceFile) new org.openrewrite.yaml.CommentOutProperty(
                kind.configurationProperty, kind.missingPropertyComment, true)
                .getVisitor().visitNonNull(withProperty, ctx);
    }

    private static SourceFile markConfigurationIssues(SourceFile source, List<ConfigurationIssue> issues,
                                                      ExecutionContext ctx) {
        Map<UUID, ValueKind> kinds = new ConcurrentHashMap<>();
        for (ConfigurationIssue issue : issues) {
            kinds.put(issue.getTreeId(), issue.getKind());
        }
        if (source instanceof Properties.File) {
            return (SourceFile) new PropertiesIsoVisitor<ExecutionContext>() {
                @Override
                public Properties.Entry visitEntry(Properties.Entry entry, ExecutionContext p) {
                    Properties.Entry e = super.visitEntry(entry, p);
                    ValueKind kind = kinds.get(e.getId());
                    return kind == null ? e : SearchResult.found(e, kind.invalidPropertyMessage);
                }
            }.visitNonNull(source, ctx);
        }
        return (SourceFile) new YamlIsoVisitor<ExecutionContext>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext p) {
                Yaml.Mapping.Entry e = super.visitMappingEntry(entry, p);
                ValueKind kind = kinds.get(e.getValue().getId());
                return kind == null ? e : SearchResult.found(e, kind.invalidPropertyMessage);
            }
        }.visitNonNull(source, ctx);
    }

    private static SourceFile markJavaClass(JavaSourceFile source, UUID classId, String message,
                                            ExecutionContext ctx) {
        return (SourceFile) new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext p) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, p);
                return c.getId().equals(classId) ? SearchResult.found(c, message) : c;
            }
        }.visitNonNull(source, ctx);
    }

    private static List<Occurrence> unresolvedOccurrences(JavaProject project, Accumulator acc) {
        List<Occurrence> unresolved = new ArrayList<>();
        for (Occurrence occurrence : acc.occurrences.getOrDefault(project, new ConcurrentLinkedQueue<>())) {
            if ((occurrence.getKind() == ValueKind.UUID && !acc.uuidConfigured.contains(project)) ||
                    (occurrence.getKind() == ValueKind.BIG_NUMBER && !acc.bigNumberConfigured.contains(project))) {
                unresolved.add(occurrence);
            }
        }
        return unresolved;
    }

    private static List<ConfigurationIssue> unresolvedConfigurationIssues(Path sourcePath, JavaProject project,
                                                                          Accumulator acc) {
        List<ConfigurationIssue> unresolved = new ArrayList<>();
        for (ConfigurationIssue issue : acc.configurationIssues.getOrDefault(project,
                new ConcurrentLinkedQueue<>())) {
            if (issue.getSourcePath().equals(sourcePath) &&
                    ((issue.getKind() == ValueKind.UUID && !acc.uuidConfigured.contains(project)) ||
                            (issue.getKind() == ValueKind.BIG_NUMBER && !acc.bigNumberConfigured.contains(project)))) {
                unresolved.add(issue);
            }
        }
        return unresolved;
    }

    private static boolean hasKind(List<Occurrence> occurrences, ValueKind kind) {
        return occurrences.stream().anyMatch(occurrence -> occurrence.getKind() == kind);
    }
}
