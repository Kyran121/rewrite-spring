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
package org.openrewrite.java.spring.data;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.spring.data.search.MongoValueRepresentationTestSupport;
import org.openrewrite.test.RecipeSpec;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

class AddMongoValueRepresentationPropertyBoundariesTest extends MongoValueRepresentationTestSupport {

    @Override
    public void defaults(RecipeSpec spec) {
        super.defaults(spec);
        spec.recipe(new AddMongoValueRepresentationProperty());
    }

    @Test
    void nullJavaConfigurationIsMarkedRatherThanShadowedByAPropertySuggestion() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder,
                                   MongoConverterConfigurationAdapter adapter) {
                        builder.uuidRepresentation(null);
                        adapter.bigDecimal(null);
                    }
                }
                """,
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder,
                                   MongoConverterConfigurationAdapter adapter) {
                        // `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                        builder.uuidRepresentation(null);
                        // `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                        adapter.bigDecimal(null);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void explicitUnspecifiedJavaConfigurationIsMarkedRatherThanShadowedByAPropertySuggestion() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.bson.UuidRepresentation;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder,
                                   MongoConverterConfigurationAdapter adapter) {
                        builder.uuidRepresentation(UuidRepresentation.UNSPECIFIED);
                        adapter.bigDecimal(BigDecimalRepresentation.UNSPECIFIED);
                    }
                }
                """,
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.bson.UuidRepresentation;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder,
                                   MongoConverterConfigurationAdapter adapter) {
                        // `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                        builder.uuidRepresentation(UuidRepresentation.UNSPECIFIED);
                        // `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                        adapter.bigDecimal(BigDecimalRepresentation.UNSPECIFIED);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void profileGatedJavaConfigurationDoesNotSuppressBaselineGeneration() {
        // @Profile("prod") only activates this class under that profile, so a call inside it can't be
        // trusted to protect every other profile — a base properties suggestion must still be offered.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.bson.UuidRepresentation;
                import org.springframework.context.annotation.Profile;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;

                @Profile("prod")
                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder,
                                   MongoConverterConfigurationAdapter adapter) {
                        builder.uuidRepresentation(UuidRepresentation.STANDARD);
                        adapter.bigDecimal(BigDecimalRepresentation.DECIMAL128);
                    }
                }
                """
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void profileGatedInvalidJavaConfigurationIsStillMarked() {
        // Even behind a @Profile, an explicit but faulty call is still a real bug and gets commented
        // in place. Profile-gating means it never counts as "attempted" for the project overall,
        // so a base UUID suggestion is offered too — fixing the profile-gated call only helps "prod".
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.springframework.context.annotation.Profile;

                class MongoConfiguration {
                    @Profile("prod")
                    void configure(MongoClientSettings.Builder builder) {
                        builder.uuidRepresentation(null);
                    }
                }
                """,
              """
                package com.example;

                import com.mongodb.MongoClientSettings;
                import org.springframework.context.annotation.Profile;

                class MongoConfiguration {
                    @Profile("prod")
                    void configure(MongoClientSettings.Builder builder) {
                        // `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                        builder.uuidRepresentation(null);
                    }
                }
                """
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void validValueInProfileSpecificPropertiesFileDoesNotSuppressBaselineGeneration() {
        // spring.mongodb.representation.uuid=standard in application-prod.properties only applies
        // when "prod" is active, so a base suggestion must still be offered for every other profile.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            properties(
              """
                spring.mongodb.representation.uuid=standard
                spring.data.mongodb.representation.big-decimal=decimal128
                """,
              spec -> spec.path("src/main/resources/application-prod.properties")
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void validValueInProfileSpecificYamlFileDoesNotSuppressBaselineGeneration() {
        // YAML equivalent of validValueInProfileSpecificPropertiesFileDoesNotSuppressBaselineGeneration.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            yaml(
              """
                spring:
                  mongodb:
                    representation:
                      uuid: standard
                  data:
                    mongodb:
                      representation:
                        big-decimal: decimal128
                """,
              spec -> spec.path("src/main/resources/application-prod.yml")
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void testResourceConfigurationDoesNotSuppressBaselineGeneration() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            properties(
              """
                spring.mongodb.representation.uuid=standard
                spring.data.mongodb.representation.big-decimal=decimal128
                """,
              spec -> spec.path("src/test/resources/application.properties")
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void configuredProjectDoesNotSuppressBaselineGenerationForAnUnconfiguredProject() {
        // Two unrelated projects in the same run: project-a's valid configuration must not be read
        // as satisfying project-b's identical-looking field, and the baseline generated for
        // project-b must not land in, or otherwise disturb, project-a.
        rewriteRun(
          mavenProject("project-a",
            pomXml(MINIMAL_POM.replace("<artifactId>example</artifactId>", "<artifactId>project-a</artifactId>")),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            properties(
              """
                spring.mongodb.representation.uuid=standard
                spring.data.mongodb.representation.big-decimal=decimal128
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          ),
          mavenProject("project-b",
            pomXml(MINIMAL_POM.replace("<artifactId>example</artifactId>", "<artifactId>project-b</artifactId>")),
            java(
              """
                package com.example.projectb;

                import java.math.BigDecimal;
                import java.util.UUID;
                import org.springframework.data.mongodb.core.mapping.Document;

                @Document
                class Account {
                    private UUID externalId;
                    private BigDecimal balance;
                }
                """,
              spec -> spec.path("src/main/java/com/example/projectb/Account.java")
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void unrelatedMainResourceIsNotUsedAsConfigurationTarget() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            yaml(
              """
                logging:
                  level: INFO
                """,
              spec -> spec.path("src/main/resources/logback.yml")
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void malformedYamlValuesMarkExistingEntries() {
        rewriteRun(
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
              """
                spring:
                  mongodb:
                    representation:
                      # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                      uuid:
                        unsupported: value
                  data:
                    mongodb:
                      representation:
                        # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                        big-decimal:
                          - decimal128
                """,
              spec -> spec.path("src/main/resources/application.yml")
            )
          )
        );
    }

    @Test
    void faultyJavaConfigurationForOneKindCoexistsWithBaselineGenerationForAnother() {
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(
              accountWithUuidAndBigDecimal(),
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder) {
                        builder.uuidRepresentation(null);
                    }
                }
                """,
              """
                package com.example;

                import com.mongodb.MongoClientSettings;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder) {
                        // `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                        builder.uuidRepresentation(null);
                    }
                }
                """
            ),
            properties(
              null,
              """
                # `spring.data.mongodb.representation.big-decimal` needs a concrete big-number representation matching the existing BSON data.
                spring.data.mongodb.representation.big-decimal=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void generatingABaselineFileIsIdempotentOnASecondRun() {
        // rewriteRun already runs 2 cycles and expects changes to settle after the first, so this
        // relies on nothing but that default: the file generate() creates in cycle 1 must not pick
        // up a further, differently formatted comment on top of the one from that first cycle.
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
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            properties(
              null,
              """
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void doesNotDuplicateAnAlreadyCommentedOutPlaceholderSuggestion() {
        // Steady state after a prior run created and commented the UNSPECIFIED suggestion: scanning
        // must count it as an existing attempt, else propertiesToAdd would emit a duplicate.
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
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            properties(
              """
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void doesNotDuplicateAnAlreadyCommentedOutPlaceholderSuggestionInYaml() {
        // YAML equivalent of doesNotDuplicateAnAlreadyCommentedOutPlaceholderSuggestion.
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
              spec -> spec.path("src/main/java/com/example/Account.java")
            ),
            yaml(
              """
                spring:
                  application:
                    name: example
                  mongodb:
                    representation:
                      # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                      uuid: UNSPECIFIED
                """,
              spec -> spec.path("src/main/resources/application.yml")
            )
          )
        );
    }

    @Test
    void doesNotDuplicateAnAlreadyCommentedInvalidPropertyMessage() {
        // Every cycle, the scanner still sees the same invalid entry, so re-commenting relies on
        // Comments.of(...)'s own idempotency to leave the file byte-for-byte unchanged.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(accountWithUuidAndBigDecimal()),
            properties(
              """
                # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                spring.mongodb.representation.uuid=unsupported
                spring.data.mongodb.representation.big-decimal=decimal128
                """,
              spec -> spec.path("src/main/resources/application.properties")
            )
          )
        );
    }

    @Test
    void doesNotDuplicateAnAlreadyCommentedInvalidPropertyMessageInYaml() {
        // YAML equivalent of doesNotDuplicateAnAlreadyCommentedInvalidPropertyMessage.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(accountWithUuidAndBigDecimal()),
            yaml(
              """
                spring:
                  mongodb:
                    representation:
                      # `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                      uuid: unsupported
                  data:
                    mongodb:
                      representation:
                        big-decimal: decimal128
                """,
              spec -> spec.path("src/main/resources/application.yml")
            )
          )
        );
    }

    @Test
    void doesNotDuplicateAnAlreadyCommentedInvalidJavaConfigurationMessage() {
        // Java equivalent of doesNotDuplicateAnAlreadyCommentedInvalidPropertyMessage.
        rewriteRun(
          mavenProject("app",
            pomXml(MINIMAL_POM),
            java(accountWithUuidAndBigDecimal()),
            java(
              """
                package com.example;

                import com.mongodb.MongoClientSettings;

                class MongoConfiguration {
                    void configure(MongoClientSettings.Builder builder) {
                        // `spring.mongodb.representation.uuid` needs a concrete UUID representation matching the existing BSON data.
                        builder.uuidRepresentation(null);
                    }
                }
                """,
              spec -> spec.path("src/main/java/com/example/MongoConfiguration.java")
            )
          )
        );
    }
}
