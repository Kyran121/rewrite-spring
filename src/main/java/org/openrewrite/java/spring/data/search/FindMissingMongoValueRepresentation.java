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
import org.openrewrite.java.spring.table.MongoValueRepresentationFields;
import org.openrewrite.marker.Marker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    // Value a suggested-but-unchosen property is created with: a real, bindable enum constant (not
    // placeholder text), so a project that never follows up still starts up — as unconfigured as
    // before, and treated like a user-written UNSPECIFIED (ValueKind.isConfiguredValue excludes it).
    static final String UNSPECIFIED_VALUE = "UNSPECIFIED";
    static final String UUID_TYPE = "java.util.UUID";
    static final String BIG_DECIMAL_TYPE = "java.math.BigDecimal";
    static final String BIG_INTEGER_TYPE = "java.math.BigInteger";

    private static final String UUID_INVALID_PROPERTY_MESSAGE =
            "`" + UUID_PROPERTY + "` needs a concrete UUID representation matching the existing BSON data.";

    private static final String BIG_NUMBER_INVALID_PROPERTY_MESSAGE =
            "`" + BIG_NUMBER_PROPERTY + "` needs a concrete big-number representation matching the existing BSON data.";

    final transient MongoValueRepresentationFields affectedFields = new MongoValueRepresentationFields(this);

    @Getter
    final String displayName = "Find missing MongoDB value representation configuration";

    @Getter
    final String description = "Find explicitly MongoDB-mapped UUID, BigInteger, and BigDecimal fields that require an " +
            "explicit representation when migrating to Spring Data MongoDB 5. The recipe reports affected fields " +
            "without choosing a storage representation.";

    /**
     * A baseline file from {@link #generate} is created empty: the scan feeding that cycle's
     * {@link Accumulator} predates the file, so populating it needs a further cycle to first scan
     * the now-persisted file. Without opting in, a real run would stop after the generating cycle.
     */
    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    enum ValueKind {
        // Mirrors org.bson.UuidRepresentation, excluding UNSPECIFIED (not a valid choice).
        UUID("UUID", UUID_PROPERTY, UUID_INVALID_PROPERTY_MESSAGE,
                "standard", "java-legacy", "c-sharp-legacy", "python-legacy"),
        // Mirrors Spring Data MongoDB's BigDecimalRepresentation, excluding UNSPECIFIED.
        BIG_NUMBER("BigDecimal/BigInteger", BIG_NUMBER_PROPERTY,
                BIG_NUMBER_INVALID_PROPERTY_MESSAGE, "string", "decimal128");

        final String displayName;
        final String configurationProperty;
        final String invalidPropertyMessage;
        private final Set<String> supportedValues;

        ValueKind(String displayName, String configurationProperty, String invalidPropertyMessage,
                  String... supportedValues) {
            this.displayName = displayName;
            this.configurationProperty = configurationProperty;
            this.invalidPropertyMessage = invalidPropertyMessage;
            this.supportedValues = new HashSet<>();
            for (String supportedValue : supportedValues) {
                this.supportedValues.add(normalize(supportedValue));
            }
        }

        boolean isConfiguredValue(@Nullable String value) {
            if (value == null) {
                return false;
            }
            String candidate = value.trim();
            if (candidate.startsWith("${") && candidate.endsWith("}")) {
                return true;
            }
            return supportedValues.contains(normalize(candidate));
        }

        private static String normalize(String value) {
            return value.replace("-", "")
                    .replace("_", "")
                    .replace(" ", "")
                    .toUpperCase(Locale.ROOT);
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

    public static class Accumulator {
        final Map<ValueKind, Set<JavaProject>> validlyConfigured = newProjectSetsByKind();
        final Map<ValueKind, Set<JavaProject>> propertyAttempted = newProjectSetsByKind();
        final Map<ValueKind, Set<JavaProject>> javaAttempted = newProjectSetsByKind();

        // Accumulator is rebuilt each cycle, so a ProjectDiagnostic marker left by a prior cycle's
        // edit is the only way "already fully handled" survives across cycles.
        final Set<JavaProject> finalizedProjects = ConcurrentHashMap.newKeySet();

        // Guards against duplicate data-table rows within and across cycles; seeded from a
        // RowsRecorded marker when an earlier cycle's generated baseline file is rescanned.
        final Set<JavaProject> rowsInsertedProjects = ConcurrentHashMap.newKeySet();

        // The single winning path per project (see SpringConfigFileSupport.preferredConfigurationSource).
        final Map<JavaProject, Path> preferredConfigurationSource = new ConcurrentHashMap<>();

        final Map<JavaProject, ConcurrentLinkedQueue<Occurrence>> occurrences = new ConcurrentHashMap<>();

        // Unsupported properties/YAML values and invalid Java calls (e.g. uuidRepresentation(null))
        // alike: the same record serves both, since only source path and kind differ.
        final Map<JavaProject, ConcurrentLinkedQueue<ConfigurationIssue>> configurationIssues =
                new ConcurrentHashMap<>();

        void markConfigured(ValueKind kind, JavaProject project) {
            projectsFor(validlyConfigured, kind).add(project);
        }

        boolean isUnconfigured(ValueKind kind, JavaProject project) {
            return !projectsFor(validlyConfigured, kind).contains(project);
        }

        void markPropertyAttempted(ValueKind kind, JavaProject project) {
            projectsFor(propertyAttempted, kind).add(project);
        }

        boolean isPropertyUnattempted(ValueKind kind, JavaProject project) {
            return !projectsFor(propertyAttempted, kind).contains(project);
        }

        void markJavaAttempted(ValueKind kind, JavaProject project) {
            projectsFor(javaAttempted, kind).add(project);
        }

        boolean isJavaUnattempted(ValueKind kind, JavaProject project) {
            return !projectsFor(javaAttempted, kind).contains(project);
        }

        void addOccurrence(JavaProject project, Occurrence occurrence) {
            occurrences.computeIfAbsent(project, ignored -> new ConcurrentLinkedQueue<>()).add(occurrence);
        }

        void addConfigurationIssue(JavaProject project, ConfigurationIssue issue) {
            configurationIssues.computeIfAbsent(project, ignored -> new ConcurrentLinkedQueue<>()).add(issue);
        }

        private static Map<ValueKind, Set<JavaProject>> newProjectSetsByKind() {
            Map<ValueKind, Set<JavaProject>> byKind = new EnumMap<>(ValueKind.class);
            for (ValueKind kind : ValueKind.values()) {
                byKind.put(kind, ConcurrentHashMap.newKeySet());
            }
            return byKind;
        }

        // newProjectSetsByKind() always populates every ValueKind, so the lookup can never miss.
        private static Set<JavaProject> projectsFor(Map<ValueKind, Set<JavaProject>> byKind, ValueKind kind) {
            return Objects.requireNonNull(byKind.get(kind));
        }
    }

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
                JavaProject project = SpringConfigFileSupport.javaProject(source);
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
                JavaProject project = SpringConfigFileSupport.javaProject(source);
                return project == null ? source : MongoValueRepresentationDiagnostics.apply(
                        FindMissingMongoValueRepresentation.this, source, project, acc, ctx);
            }
        };
    }

    /**
     * Generates a baseline configuration file for each project that needs one
     * (see {@link MongoValueRepresentationDiagnostics#generateBaselineConfiguration}).
     */
    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();
        for (JavaProject project : acc.occurrences.keySet()) {
            SourceFile baseline = MongoValueRepresentationDiagnostics.generateBaselineConfiguration(project, acc, ctx);
            if (baseline != null) {
                generated.add(baseline);
            }
        }
        return generated;
    }

    @Value
    @With
    static class ProjectDiagnostic implements Marker {
        UUID id;
    }

    /**
     * Marks a generated baseline file so a later scan knows its project's data-table rows were
     * already recorded, even though {@link Accumulator} doesn't survive across cycles.
     */
    @Value
    @With
    static class RowsRecorded implements Marker {
        UUID id;
    }
}
