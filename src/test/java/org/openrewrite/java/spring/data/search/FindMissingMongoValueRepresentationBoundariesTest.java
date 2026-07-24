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

import org.junit.jupiter.api.Test;
import org.openrewrite.java.spring.table.MongoValueRepresentationFields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

class FindMissingMongoValueRepresentationBoundariesTest extends MongoValueRepresentationTestSupport {

    @Test
    void ignoresNonMongoAndTransientFields() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.math.BigDecimal;
                import java.util.UUID;
                import org.springframework.data.annotation.Persistent;
                import org.springframework.data.annotation.Transient;
                import org.springframework.data.mongodb.core.mapping.Document;

                class NotPersistent {
                    private UUID externalId;
                    private BigDecimal balance;
                }

                @Persistent
                class OtherDataStoreEntity {
                    private UUID externalId;
                    private BigDecimal balance;
                }

                @Document
                class Account {
                    private static UUID staticId;
                    private transient BigDecimal transientBalance;
                    @Transient
                    private UUID ignoredId;

                    void calculate() {
                        BigDecimal local = BigDecimal.ZERO;
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void ignoresExplicitFieldRepresentationAndBigIntegerIds() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.math.BigDecimal;
                import java.math.BigInteger;
                import org.springframework.data.annotation.Id;
                import org.springframework.data.mongodb.core.mapping.Document;
                import org.springframework.data.mongodb.core.mapping.Field;
                import org.springframework.data.mongodb.core.mapping.FieldType;

                @Document
                class Account {
                    @Field(targetType = FieldType.DECIMAL128)
                    private BigDecimal balance;

                    @Id
                    private BigInteger identifier;

                    private BigInteger id;
                }
                """
            )
          )
        );
    }

    @Test
    void reportsNestedValuesButNotMapKeys() {
        rewriteRun(
          spec -> spec.dataTable(MongoValueRepresentationFields.Row.class, rows ->
            assertThat(rows)
              .extracting(MongoValueRepresentationFields.Row::getField)
              .containsExactlyInAnyOrder("externalIds", "balances")),
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Map;
                import java.util.UUID;
                import org.springframework.data.mongodb.core.mapping.Document;

                @Document
                class Account {
                    private List<UUID> externalIds;
                    private Map<UUID, String> labelsByExternalId;
                    private Map<String, BigDecimal> balances;
                }
                """,
              spec -> spec.after(FindMissingMongoValueRepresentationBoundariesTest::assertClassLevelDiagnostic)
            )
          )
        );
    }

    @Test
    void testJavaConfigurationDoesNotSuppressMainDiagnostics() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.util.UUID;
                import org.springframework.data.mongodb.core.mapping.Document;

                @Document
                class Account {
                    private UUID externalId;
                }
                """,
              spec -> spec.after(FindMissingMongoValueRepresentationBoundariesTest::assertClassLevelDiagnostic)
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.bson.UuidRepresentation;

                class TestMongoConfiguration {
                    void configure(MongoClientSettings.Builder builder) {
                        builder.uuidRepresentation(UuidRepresentation.STANDARD);
                    }
                }
                """,
              spec -> spec.path("src/test/java/com/example/TestMongoConfiguration.java")
            )
          )
        );
    }

    @Test
    void testResourceConfigurationDoesNotSuppressMainDiagnostics() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.after(FindMissingMongoValueRepresentationBoundariesTest::assertClassLevelDiagnostic)
            ),
            properties(
              """
                spring.mongodb.representation.uuid=standard
                spring.data.mongodb.representation.big-decimal=decimal128
                """,
              spec -> spec.path("src/test/resources/application.properties")
            )
          )
        );
    }

    @Test
    void malformedYamlValuesMarkExistingEntries() {
        rewriteRun(
          spec -> spec.dataTable(MongoValueRepresentationFields.Row.class, rows -> assertThat(rows).hasSize(2)),
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(accountWithUuidAndBigDecimal()),
            yaml(
              """
                spring:
                  mongodb:
                    representation:
                      uuid:
                        unsupported: value
                  data:
                    mongodb:
                      representation:
                        big-decimal:
                          - decimal128
                """,
              spec -> spec
                .path("src/main/resources/application.yml")
                .afterRecipe(file -> {
                    assertYamlEntryMarked(file, "uuid");
                    assertYamlEntryMarked(file, "big-decimal");
                })
            )
          )
        );
    }

    @Test
    void reportsSharedDeclarationWhenOneBigIntegerIsNotAnId() {
        rewriteRun(
          spec -> spec.dataTable(MongoValueRepresentationFields.Row.class, rows ->
            assertThat(rows)
              .singleElement()
              .extracting(MongoValueRepresentationFields.Row::getField)
              .isEqualTo("sequence")),
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.math.BigInteger;
                import org.springframework.data.mongodb.core.mapping.Document;

                @Document
                class Account {
                    private BigInteger id, sequence;
                }
                """,
              spec -> spec.after(FindMissingMongoValueRepresentationBoundariesTest::assertClassLevelDiagnostic)
            )
          )
        );
    }

    @Test
    void isIdempotent() {
        rewriteRun(
          spec -> spec.cycles(2).expectedCyclesThatMakeChanges(1),
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              """
                package com.example;

                import java.util.UUID;
                import org.springframework.data.mongodb.core.mapping.Document;

                @Document
                class Account {
                    private UUID externalId;
                }
                """,
              spec -> spec.after(FindMissingMongoValueRepresentationBoundariesTest::assertClassLevelDiagnostic)
            )
          )
        );
    }
}
