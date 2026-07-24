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

import lombok.Getter;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.spring.SpringConfigFile;
import org.openrewrite.java.spring.table.MongoValueRepresentationFields;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.marker.SourceSet;
import org.openrewrite.properties.PropertiesIsoVisitor;
import org.openrewrite.properties.search.FindProperties;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.search.FindProperty;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Find explicitly MongoDB-mapped UUID and big-number fields for which Spring Data MongoDB 5
 * no longer supplies a default representation.
 */
public class FindMissingMongoValueRepresentation extends ScanningRecipe<FindMissingMongoValueRepresentation.Accumulator> {

    private static final String UUID_PROPERTY = "spring.mongodb.representation.uuid";
    private static final String BIG_NUMBER_PROPERTY = "spring.data.mongodb.representation.big-decimal";

    private static final String UUID_TYPE = "java.util.UUID";
    private static final String BIG_DECIMAL_TYPE = "java.math.BigDecimal";
    private static final String BIG_INTEGER_TYPE = "java.math.BigInteger";

    private static final String UUID_MESSAGE =
            "Spring Data MongoDB 5 requires an explicit UUID representation; configure `" + UUID_PROPERTY +
            "` or `MongoClientSettings.Builder.uuidRepresentation(...)`.";
    private static final String BIG_NUMBER_MESSAGE =
            "Spring Data MongoDB 5 requires an explicit BigDecimal/BigInteger representation; configure `" +
            BIG_NUMBER_PROPERTY + "` or `MongoConverterConfigurationAdapter.bigDecimal(...)`.";
    private static final String UUID_AND_BIG_NUMBER_MESSAGE =
            "Spring Data MongoDB 5 requires explicit UUID and BigDecimal/BigInteger representations; configure `" +
            UUID_PROPERTY + "` and `" + BIG_NUMBER_PROPERTY + "`, or their Java equivalents.";

    private static final MethodMatcher UUID_REPRESENTATION =
            new MethodMatcher("com.mongodb.MongoClientSettings$Builder uuidRepresentation(..)");
    private static final MethodMatcher BIG_NUMBER_REPRESENTATION = new MethodMatcher(
            "org.springframework.data.mongodb.core.convert.MongoCustomConversions$MongoConverterConfigurationAdapter bigDecimal(..)");

    private static final AnnotationMatcher DOCUMENT =
            new AnnotationMatcher("@org.springframework.data.mongodb.core.mapping.Document");
    private static final AnnotationMatcher FIELD =
            new AnnotationMatcher("@org.springframework.data.mongodb.core.mapping.Field");
    private static final AnnotationMatcher MONGO_ID =
            new AnnotationMatcher("@org.springframework.data.mongodb.core.mapping.MongoId");
    private static final AnnotationMatcher DB_REF =
            new AnnotationMatcher("@org.springframework.data.mongodb.core.mapping.DBRef");
    private static final AnnotationMatcher DOCUMENT_REFERENCE =
            new AnnotationMatcher("@org.springframework.data.mongodb.core.mapping.DocumentReference");
    private static final AnnotationMatcher TRANSIENT =
            new AnnotationMatcher("@org.springframework.data.annotation.Transient");
    private static final AnnotationMatcher ID =
            new AnnotationMatcher("@org.springframework.data.annotation.Id");

    private final transient MongoValueRepresentationFields affectedFields =
            new MongoValueRepresentationFields(this);

    @Getter
    final String displayName = "Find missing MongoDB value representation configuration";

    @Getter
    final String description = "Find explicitly MongoDB-mapped UUID, BigInteger, and BigDecimal fields that require an " +
            "explicit representation when migrating to Spring Data MongoDB 5. The recipe adds one project-level " +
            "diagnostic and records every affected field in a data table without choosing a storage representation.";

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }

                SourceFile source = (SourceFile) tree;
                JavaProject project = javaProject(source);
                if (project == null) {
                    return source;
                }

                if (source.getMarkers().findFirst(ProjectDiagnostic.class).isPresent()) {
                    acc.reportedProjects.add(project);
                }

                if (source instanceof JavaSourceFile && isMainSource(source)) {
                    scanJavaConfiguration((JavaSourceFile) source, project, acc, ctx);
                    scanAffectedFields((JavaSourceFile) source, project, acc, ctx);
                } else if (isMainSpringConfigurationFile(source)) {
                    scanPropertyConfiguration(source, project, acc);
                    acc.configurationSources.merge(project, source.getSourcePath(),
                            FindMissingMongoValueRepresentation::preferredConfigurationSource);
                }
                return source;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }

                SourceFile source = (SourceFile) tree;
                JavaProject project = javaProject(source);
                if (project == null || acc.reportedProjects.contains(project)) {
                    return source;
                }

                List<Occurrence> unresolved = unresolvedOccurrences(project, acc);
                if (unresolved.isEmpty()) {
                    return source;
                }

                Path targetSource = acc.configurationSources.get(project);
                Occurrence targetOccurrence = null;
                if (targetSource == null) {
                    targetOccurrence = unresolved.stream()
                            .min(Comparator.comparing((Occurrence occurrence) -> occurrence.sourcePath.toString())
                                    .thenComparing(Occurrence::getOwningType)
                                    .thenComparing(Occurrence::getField)
                                    .thenComparing(occurrence -> occurrence.kind.name()))
                            .orElse(null);
                    if (targetOccurrence == null) {
                        return source;
                    }
                    targetSource = targetOccurrence.sourcePath;
                }

                if (!source.getSourcePath().equals(targetSource)) {
                    return source;
                }

                for (Occurrence occurrence : unresolved) {
                    affectedFields.insertRow(ctx, new MongoValueRepresentationFields.Row(
                            occurrence.sourcePath.toString(),
                            occurrence.owningType,
                            occurrence.field,
                            occurrence.kind.displayName,
                            occurrence.kind.configurationProperty));
                }

                boolean missingUuid = unresolved.stream().anyMatch(occurrence -> occurrence.kind == ValueKind.UUID);
                boolean missingBigNumber = unresolved.stream().anyMatch(occurrence -> occurrence.kind == ValueKind.BIG_NUMBER);
                String message = diagnosticMessage(missingUuid, missingBigNumber);

                SourceFile marked;
                if (source instanceof Properties.File) {
                    marked = markProperties((Properties.File) source, message, ctx);
                } else if (source instanceof Yaml.Documents) {
                    marked = markYaml((Yaml.Documents) source, message, ctx);
                } else if (source instanceof JavaSourceFile && targetOccurrence != null) {
                    marked = markJavaDeclaration((JavaSourceFile) source, targetOccurrence.declarationId, message, ctx);
                } else {
                    marked = SearchResult.found(source, message);
                }

                return marked.withMarkers(marked.getMarkers().addIfAbsent(new ProjectDiagnostic(Tree.randomId())));
            }
        };
    }

    private static void scanJavaConfiguration(JavaSourceFile source, JavaProject project, Accumulator acc,
                                              ExecutionContext ctx) {
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {
                J.MethodInvocation m = super.visitMethodInvocation(method, executionContext);
                if (UUID_REPRESENTATION.matches(m) && hasExplicitArgument(m)) {
                    acc.uuidConfigured.add(project);
                }
                if (BIG_NUMBER_REPRESENTATION.matches(m) && hasExplicitArgument(m)) {
                    acc.bigNumberConfigured.add(project);
                }
                return m;
            }
        }.visit(source, ctx);
    }

    private static void scanAffectedFields(JavaSourceFile source, JavaProject project, Accumulator acc,
                                           ExecutionContext ctx) {
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                     ExecutionContext executionContext) {
                J.VariableDeclarations d = super.visitVariableDeclarations(declarations, executionContext);
                J.ClassDeclaration owningClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (owningClass == null || getCursor().firstEnclosing(J.MethodDeclaration.class) != null ||
                        !isExplicitlyMongoMapped(owningClass, d) || isIgnoredField(d)) {
                    return d;
                }

                String owningType = owningClass.getType() == null ? owningClass.getSimpleName() :
                        owningClass.getType().getFullyQualifiedName();
                boolean containsUuid = containsPersistedType(d.getType(), UUID_TYPE);
                boolean containsBigNumber = (containsPersistedType(d.getType(), BIG_DECIMAL_TYPE) ||
                                             containsPersistedType(d.getType(), BIG_INTEGER_TYPE)) &&
                                            !hasExplicitFieldTargetType(d);

                if (containsUuid) {
                    for (J.VariableDeclarations.NamedVariable variable : d.getVariables()) {
                        acc.addOccurrence(project, new Occurrence(source.getSourcePath(), d.getId(), owningType,
                                variable.getSimpleName(), ValueKind.UUID));
                    }
                }

                if (containsBigNumber) {
                    for (J.VariableDeclarations.NamedVariable variable : d.getVariables()) {
                        if (!TypeUtils.isOfClassType(d.getType(), BIG_INTEGER_TYPE) || !isBigIntegerId(d, variable)) {
                            acc.addOccurrence(project, new Occurrence(source.getSourcePath(), d.getId(), owningType,
                                    variable.getSimpleName(), ValueKind.BIG_NUMBER));
                        }
                    }
                }
                return d;
            }
        }.visit(source, ctx);
    }

    private static SourceFile markProperties(Properties.File source, String message, ExecutionContext ctx) {
        boolean[] marked = {false};
        Properties.File after = (Properties.File) new PropertiesIsoVisitor<ExecutionContext>() {
            @Override
            public Properties.Entry visitEntry(Properties.Entry entry, ExecutionContext executionContext) {
                Properties.Entry e = super.visitEntry(entry, executionContext);
                if (!marked[0]) {
                    marked[0] = true;
                    return SearchResult.found(e, message);
                }
                return e;
            }
        }.visitNonNull(source, ctx);
        return marked[0] ? after : SearchResult.found(source, message);
    }

    private static SourceFile markYaml(Yaml.Documents source, String message, ExecutionContext ctx) {
        boolean[] marked = {false};
        Yaml.Documents after = (Yaml.Documents) new YamlIsoVisitor<ExecutionContext>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext executionContext) {
                if (!marked[0]) {
                    marked[0] = true;
                    return super.visitMappingEntry(SearchResult.found(entry, message), executionContext);
                }
                return super.visitMappingEntry(entry, executionContext);
            }
        }.visitNonNull(source, ctx);
        return marked[0] ? after : SearchResult.found(source, message);
    }

    private static SourceFile markJavaDeclaration(JavaSourceFile source, UUID declarationId, String message,
                                                  ExecutionContext ctx) {
        return (SourceFile) new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                     ExecutionContext executionContext) {
                J.VariableDeclarations d = super.visitVariableDeclarations(declarations, executionContext);
                return d.getId().equals(declarationId) ? SearchResult.found(d, message) : d;
            }
        }.visitNonNull(source, ctx);
    }

    private static void scanPropertyConfiguration(SourceFile source, JavaProject project, Accumulator acc) {
        if (source instanceof Properties.File) {
            Properties.File properties = (Properties.File) source;
            if (hasExplicitProperty(properties, UUID_PROPERTY)) {
                acc.uuidConfigured.add(project);
            }
            if (hasExplicitProperty(properties, BIG_NUMBER_PROPERTY)) {
                acc.bigNumberConfigured.add(project);
            }
        } else if (source instanceof Yaml.Documents) {
            Yaml.Documents yaml = (Yaml.Documents) source;
            if (hasExplicitYamlProperty(yaml, UUID_PROPERTY)) {
                acc.uuidConfigured.add(project);
            }
            if (hasExplicitYamlProperty(yaml, BIG_NUMBER_PROPERTY)) {
                acc.bigNumberConfigured.add(project);
            }
        }
    }

    private static boolean hasExplicitArgument(J.MethodInvocation method) {
        return !method.getArguments().isEmpty() && !isUnspecified(method.getArguments().get(0));
    }

    private static boolean isUnspecified(Expression expression) {
        String name = simpleName(expression);
        if (name == null && expression instanceof J.Literal && ((J.Literal) expression).getValue() != null) {
            name = ((J.Literal) expression).getValue().toString();
        }
        return name != null && "unspecified".equalsIgnoreCase(name.trim());
    }

    private static boolean hasExplicitProperty(Properties.File file, String property) {
        for (Properties.Entry entry : FindProperties.find(file, property, true)) {
            if (isExplicitValue(entry.getValue().getText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExplicitYamlProperty(Yaml.Documents documents, String property) {
        for (Yaml.Block value : FindProperty.find(documents, property, true)) {
            if (value instanceof Yaml.Scalar && isExplicitValue(((Yaml.Scalar) value).getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitValue(@Nullable String value) {
        return value != null && !value.trim().isEmpty() && !"unspecified".equalsIgnoreCase(value.trim());
    }

    private static boolean isExplicitlyMongoMapped(J.ClassDeclaration owningClass,
                                                    J.VariableDeclarations declarations) {
        return owningClass.getLeadingAnnotations().stream().anyMatch(DOCUMENT::matches) ||
                declarations.getLeadingAnnotations().stream().anyMatch(annotation ->
                        FIELD.matches(annotation) || MONGO_ID.matches(annotation) || DB_REF.matches(annotation) ||
                                DOCUMENT_REFERENCE.matches(annotation));
    }

    private static boolean isIgnoredField(J.VariableDeclarations declarations) {
        return declarations.hasModifier(J.Modifier.Type.Static) ||
                declarations.hasModifier(J.Modifier.Type.Transient) ||
                declarations.getLeadingAnnotations().stream().anyMatch(TRANSIENT::matches);
    }

    private static boolean isBigIntegerId(J.VariableDeclarations declarations,
                                          J.VariableDeclarations.NamedVariable variable) {
        return declarations.getLeadingAnnotations().stream().anyMatch(annotation ->
                ID.matches(annotation) || MONGO_ID.matches(annotation)) || "id".equals(variable.getSimpleName());
    }

    private static boolean hasExplicitFieldTargetType(J.VariableDeclarations declarations) {
        for (J.Annotation annotation : declarations.getLeadingAnnotations()) {
            if (!FIELD.matches(annotation) || annotation.getArguments() == null) {
                continue;
            }
            for (Expression argument : annotation.getArguments()) {
                if (!(argument instanceof J.Assignment)) {
                    continue;
                }
                J.Assignment assignment = (J.Assignment) argument;
                if (assignment.getVariable() instanceof J.Identifier &&
                        "targetType".equals(((J.Identifier) assignment.getVariable()).getSimpleName())) {
                    String targetType = simpleName(assignment.getAssignment());
                    return targetType != null && !"implicit".equalsIgnoreCase(targetType);
                }
            }
        }
        return false;
    }

    private static @Nullable String simpleName(Expression expression) {
        if (expression instanceof J.Identifier) {
            return ((J.Identifier) expression).getSimpleName();
        }
        if (expression instanceof J.FieldAccess) {
            return ((J.FieldAccess) expression).getSimpleName();
        }
        return null;
    }

    private static boolean containsPersistedType(@Nullable JavaType type, String fullyQualifiedType) {
        if (type == null) {
            return false;
        }
        if (TypeUtils.isOfClassType(type, fullyQualifiedType)) {
            return true;
        }
        if (type instanceof JavaType.Array) {
            return containsPersistedType(((JavaType.Array) type).getElemType(), fullyQualifiedType);
        }
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
            int firstPersistedParameter = TypeUtils.isAssignableTo("java.util.Map", parameterized.getType()) ? 1 : 0;
            for (int i = firstPersistedParameter; i < parameterized.getTypeParameters().size(); i++) {
                if (containsPersistedType(parameterized.getTypeParameters().get(i), fullyQualifiedType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Occurrence> unresolvedOccurrences(JavaProject project, Accumulator acc) {
        List<Occurrence> unresolved = new ArrayList<>();
        for (Occurrence occurrence : acc.occurrences.getOrDefault(project, new ConcurrentLinkedQueue<>())) {
            if ((occurrence.kind == ValueKind.UUID && !acc.uuidConfigured.contains(project)) ||
                    (occurrence.kind == ValueKind.BIG_NUMBER && !acc.bigNumberConfigured.contains(project))) {
                unresolved.add(occurrence);
            }
        }
        return unresolved;
    }

    private static String diagnosticMessage(boolean missingUuid, boolean missingBigNumber) {
        if (missingUuid && missingBigNumber) {
            return UUID_AND_BIG_NUMBER_MESSAGE;
        }
        return missingUuid ? UUID_MESSAGE : BIG_NUMBER_MESSAGE;
    }

    private static Path preferredConfigurationSource(Path left, Path right) {
        int leftPriority = configurationSourcePriority(left);
        int rightPriority = configurationSourcePriority(right);
        if (leftPriority != rightPriority) {
            return leftPriority < rightPriority ? left : right;
        }
        return left.toString().compareTo(right.toString()) <= 0 ? left : right;
    }

    private static int configurationSourcePriority(Path path) {
        String filename = path.getFileName().toString();
        if ("application.properties".equals(filename)) {
            return 0;
        }
        if ("application.yml".equals(filename)) {
            return 1;
        }
        if ("application.yaml".equals(filename)) {
            return 2;
        }
        return 3;
    }

    private static boolean isMainSpringConfigurationFile(SourceFile source) {
        if (!(source instanceof Properties.File) && !(source instanceof Yaml.Documents)) {
            return false;
        }
        return source.getMarkers().findFirst(SpringConfigFile.class).isPresent() || isMainSource(source);
    }

    private static boolean isMainSource(SourceFile source) {
        SourceSet sourceSet = source.getMarkers().findFirst(SourceSet.class).orElse(null);
        if (sourceSet != null) {
            return "main".equals(sourceSet.getName());
        }
        String sourcePath = source.getSourcePath().toString().replace('\\', '/');
        return !sourcePath.startsWith("src/test/") && !sourcePath.contains("/src/test/");
    }

    private static @Nullable JavaProject javaProject(SourceFile source) {
        return source.getMarkers().findFirst(JavaProject.class).orElse(null);
    }

    enum ValueKind {
        UUID("UUID", UUID_PROPERTY),
        BIG_NUMBER("BigDecimal/BigInteger", BIG_NUMBER_PROPERTY);

        final String displayName;
        final String configurationProperty;

        ValueKind(String displayName, String configurationProperty) {
            this.displayName = displayName;
            this.configurationProperty = configurationProperty;
        }
    }

    @Value
    static class Occurrence {
        Path sourcePath;
        UUID declarationId;
        String owningType;
        String field;
        ValueKind kind;
    }

    @Value
    @With
    static class ProjectDiagnostic implements Marker {
        UUID id;
    }

    static class Accumulator {
        final Set<JavaProject> uuidConfigured = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> bigNumberConfigured = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> reportedProjects = ConcurrentHashMap.newKeySet();
        final Map<JavaProject, Path> configurationSources = new ConcurrentHashMap<>();
        final Map<JavaProject, ConcurrentLinkedQueue<Occurrence>> occurrences = new ConcurrentHashMap<>();

        void addOccurrence(JavaProject project, Occurrence occurrence) {
            occurrences.computeIfAbsent(project, ignored -> new ConcurrentLinkedQueue<>()).add(occurrence);
        }
    }
}
