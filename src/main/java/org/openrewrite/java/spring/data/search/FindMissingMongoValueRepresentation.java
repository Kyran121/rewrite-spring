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
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.spring.SpringConfigFile;
import org.openrewrite.java.spring.table.MongoValueRepresentationFields;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.SourceSet;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
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

    static final String UUID_PROPERTY = "spring.mongodb.representation.uuid";
    static final String BIG_NUMBER_PROPERTY = "spring.data.mongodb.representation.big-decimal";
    static final String REPRESENTATION_PLACEHOLDER = "<representation>";
    static final String UUID_TYPE = "java.util.UUID";
    static final String BIG_DECIMAL_TYPE = "java.math.BigDecimal";
    static final String BIG_INTEGER_TYPE = "java.math.BigInteger";

    static final String UUID_MESSAGE =
            "Spring Data MongoDB 5 requires an explicit UUID representation; configure `" + UUID_PROPERTY +
            "` or `MongoClientSettings.Builder.uuidRepresentation(...)`.";
    static final String BIG_NUMBER_MESSAGE =
            "Spring Data MongoDB 5 requires an explicit BigDecimal/BigInteger representation; configure `" +
            BIG_NUMBER_PROPERTY + "` or `MongoConverterConfigurationAdapter.bigDecimal(...)`.";
    static final String UUID_AND_BIG_NUMBER_MESSAGE =
            "Spring Data MongoDB 5 requires explicit UUID and BigDecimal/BigInteger representations; configure `" +
            UUID_PROPERTY + "` and `" + BIG_NUMBER_PROPERTY + "`, or their Java equivalents.";

    final transient MongoValueRepresentationFields affectedFields = new MongoValueRepresentationFields(this);

    @Getter
    final String displayName = "Find missing MongoDB value representation configuration";

    @Getter
    final String description = "Find explicitly MongoDB-mapped UUID, BigInteger, and BigDecimal fields that require an " +
            "explicit representation when migrating to Spring Data MongoDB 5. The recipe reports affected fields " +
            "without choosing a storage representation.";

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
                if (project != null) {
                    MongoValueRepresentationScanner.scan(source, project, acc, ctx);
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
                return project == null ? source : MongoValueRepresentationDiagnostics.apply(
                        FindMissingMongoValueRepresentation.this, source, project, acc, ctx);
            }
        };
    }

    static String diagnosticMessage(boolean missingUuid, boolean missingBigNumber) {
        return missingUuid && missingBigNumber ? UUID_AND_BIG_NUMBER_MESSAGE :
                missingUuid ? UUID_MESSAGE : BIG_NUMBER_MESSAGE;
    }

    static Path preferredConfigurationSource(Path left, Path right) {
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
        return "application.yaml".equals(filename) ? 2 : 3;
    }

    static boolean isMainSpringConfigurationFile(SourceFile source) {
        return (source instanceof Properties.File || source instanceof Yaml.Documents) &&
                (source.getMarkers().findFirst(SpringConfigFile.class).isPresent() || isMainSource(source));
    }

    static boolean isMainSource(SourceFile source) {
        SourceSet sourceSet = source.getMarkers().findFirst(SourceSet.class).orElse(null);
        if (sourceSet != null) {
            return "main".equals(sourceSet.getName());
        }
        String path = source.getSourcePath().toString().replace('\\', '/');
        return !path.startsWith("src/test/") && !path.contains("/src/test/");
    }

    static @Nullable JavaProject javaProject(SourceFile source) {
        return source.getMarkers().findFirst(JavaProject.class).orElse(null);
    }

    enum ValueKind {
        UUID("UUID", UUID_PROPERTY,
                "Choose the UUID representation matching the existing BSON data.",
                "`" + UUID_PROPERTY + "` is blank, malformed, or `UNSPECIFIED`; choose a concrete UUID " +
                        "representation that matches the existing BSON data."),
        BIG_NUMBER("BigDecimal/BigInteger", BIG_NUMBER_PROPERTY,
                "Choose the big-number representation matching the existing BSON data.",
                "`" + BIG_NUMBER_PROPERTY + "` is blank, malformed, or `UNSPECIFIED`; choose a concrete big-number " +
                        "representation that matches the existing BSON data.");

        final String displayName;
        final String configurationProperty;
        final String missingPropertyComment;
        final String invalidPropertyMessage;

        ValueKind(String displayName, String configurationProperty, String missingPropertyComment,
                  String invalidPropertyMessage) {
            this.displayName = displayName;
            this.configurationProperty = configurationProperty;
            this.missingPropertyComment = missingPropertyComment;
            this.invalidPropertyMessage = invalidPropertyMessage;
        }

        void markConfigured(JavaProject project, Accumulator acc) {
            (this == UUID ? acc.uuidConfigured : acc.bigNumberConfigured).add(project);
        }

        void markPresent(JavaProject project, Accumulator acc) {
            (this == UUID ? acc.uuidPropertyPresent : acc.bigNumberPropertyPresent).add(project);
        }
    }

    @Value
    static class Occurrence {
        Path sourcePath;
        UUID owningClassId;
        String owningType;
        String field;
        ValueKind kind;
    }

    @Value
    static class ConfigurationIssue {
        Path sourcePath;
        UUID treeId;
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
        final Set<JavaProject> uuidPropertyPresent = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> bigNumberPropertyPresent = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> reportedProjects = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> rowsInsertedProjects = ConcurrentHashMap.newKeySet();
        final Set<JavaProject> javaFallbackMarkedProjects = ConcurrentHashMap.newKeySet();
        final Map<JavaProject, Path> configurationSources = new ConcurrentHashMap<>();
        final Map<JavaProject, ConcurrentLinkedQueue<Occurrence>> occurrences = new ConcurrentHashMap<>();
        final Map<JavaProject, ConcurrentLinkedQueue<ConfigurationIssue>> configurationIssues =
                new ConcurrentHashMap<>();

        void addOccurrence(JavaProject project, Occurrence occurrence) {
            occurrences.computeIfAbsent(project, ignored -> new ConcurrentLinkedQueue<>()).add(occurrence);
        }

        void addConfigurationIssue(JavaProject project, ConfigurationIssue issue) {
            configurationIssues.computeIfAbsent(project, ignored -> new ConcurrentLinkedQueue<>()).add(issue);
        }
    }
}
