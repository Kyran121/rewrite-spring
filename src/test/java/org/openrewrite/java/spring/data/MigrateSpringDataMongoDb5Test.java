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
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateSpringDataMongoDb5Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.java.spring.data.MigrateSpringDataMongoDb5")
          .beforeRecipe(withToolingApi());
    }

    @DocumentExample
    @Test
    void upgradesExplicitMavenDependencies() {
        rewriteRun(
          mavenProject("explicit-maven-dependencies",
            pomXml(
              pom("", "", String.join("",
                dependency("org.springframework.data", "spring-data-mongodb", "4.5.13"),
                dependency("org.mongodb", "mongodb-driver-core", "4.11.5"),
                dependency("org.mongodb", "mongodb-driver-sync", "4.11.5"),
                dependency("org.mongodb", "mongodb-driver-reactivestreams", "4.11.5"),
                dependency("org.mongodb", "mongodb-crypt", "1.11.0"),
                dependency("org.mongodb", "bson", "4.11.5"),
                dependency("org.mongodb", "bson-record-codec", "4.11.5"),
                dependency("org.mongodb", "mongodb-driver-legacy", "4.11.5")
              )),
              spec -> spec.after(actual -> {
                  assertVersion(actual, "spring-data-mongodb", "5\\.0\\.\\d+");
                  assertVersion(actual, "mongodb-driver-core", "5\\.6\\.\\d+");
                  assertVersion(actual, "mongodb-driver-sync", "5\\.6\\.\\d+");
                  assertVersion(actual, "mongodb-driver-reactivestreams", "5\\.6\\.\\d+");
                  assertVersion(actual, "mongodb-crypt", "5\\.6\\.\\d+");
                  assertVersion(actual, "bson", "5\\.6\\.\\d+");
                  assertVersion(actual, "bson-record-codec", "5\\.6\\.\\d+");
                  assertVersion(actual, "mongodb-driver-legacy", "5\\.6\\.\\d+");
                  return actual;
              })
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void upgradesExplicitGradleDependencies() {
        rewriteRun(
          mavenProject("explicit-gradle-dependencies",
            buildGradle(
              //language=groovy
              """
                plugins {
                    id 'java-library'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation 'org.springframework.data:spring-data-mongodb:4.5.13'
                    implementation 'org.mongodb:mongodb-driver-reactivestreams:5.5.1'
                    implementation 'org.mongodb:bson-record-codec:4.11.5'
                    implementation 'org.mongodb:mongodb-driver-legacy:4.11.5'
                }
                """,
              spec -> spec.after(actual ->
                assertThat(actual)
                  .containsPattern("spring-data-mongodb:5\\.0\\.\\d+")
                  .containsPattern("mongodb-driver-reactivestreams:5\\.6\\.\\d+")
                  .containsPattern("bson-record-codec:5\\.6\\.\\d+")
                  .containsPattern("mongodb-driver-legacy:5\\.6\\.\\d+")
                  .actual())
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void upgradesExplicitMongoDbBomAndLeavesManagedComponentsVersionless() {
        rewriteRun(
          mavenProject("mongodb-driver-bom",
            pomXml(
              pom("", mongoDbBom("5.5.1"), String.join("",
                dependency("org.springframework.data", "spring-data-mongodb", "4.5.13"),
                versionlessDependency("org.mongodb", "mongodb-driver-sync"),
                versionlessDependency("org.mongodb", "bson-record-codec")
              )),
              spec -> spec.after(actual -> {
                  assertVersion(actual, "mongodb-driver-bom", "5\\.6\\.\\d+");
                  assertVersionless(actual, "mongodb-driver-sync");
                  assertVersionless(actual, "bson-record-codec");
                  return actual;
              })
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void leavesBootManagedVersionlessDependenciesUnchanged() {
        rewriteRun(
          mavenProject("managed-dependencies",
            pomXml(
              pom(bootParent("4.0.7"), "", String.join("",
                versionlessDependency("org.springframework.data", "spring-data-mongodb"),
                versionlessDependency("org.mongodb", "mongodb-driver-sync")
              ))
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void removesOverridesThatBecomeRedundantWithBootManagement() {
        rewriteRun(
          mavenProject("managed-overrides",
            pomXml(
              pom(bootParent("4.0.7"), "", String.join("",
                dependency("org.springframework.data", "spring-data-mongodb", "4.5.13"),
                dependency("org.mongodb", "mongodb-driver-sync", "4.11.5")
              )),
              spec -> spec.after(actual -> {
                  assertVersionless(actual, "spring-data-mongodb");
                  assertVersionless(actual, "mongodb-driver-sync");
                  return actual;
              })
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void upgradesDriverOverrideWhenSpringDataMongoDbIsTransitive() {
        rewriteRun(
          mavenProject("boot-starter-with-driver-override",
            pomXml(
              pom(bootParent("3.5.0"), "", String.join("",
                versionlessDependency("org.springframework.boot", "spring-boot-starter-data-mongodb"),
                dependency("org.mongodb", "mongodb-driver-sync", "4.11.5")
              )),
              spec -> spec.after(actual -> {
                  assertVersionless(actual, "spring-boot-starter-data-mongodb");
                  assertVersion(actual, "mongodb-driver-sync", "5\\.6\\.\\d+");
                  return actual;
              })
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void preservesCompatibleForwardOverrides() {
        rewriteRun(
          mavenProject("forward-overrides",
            pomXml(
              pom("", "", String.join("",
                dependency("org.springframework.data", "spring-data-mongodb", "5.1.0"),
                dependency("org.mongodb", "mongodb-driver-sync", "5.8.0")
              ))
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void leavesNonAllowListedJvmDriverArtifactsUnchanged() {
        rewriteRun(
          mavenProject("other-jvm-drivers",
            pomXml(
              pom("", "", String.join("",
                dependency("org.springframework.data", "spring-data-mongodb", "4.5.13"),
                dependency("org.mongodb", "mongo-java-driver", "3.12.14"),
                dependency("org.mongodb", "mongodb-driver-kotlin-coroutine", "5.5.1"),
                dependency("org.mongodb", "mongodb-driver-kotlin-sync", "5.5.1"),
                dependency("org.mongodb", "bson-kotlin", "5.5.1"),
                dependency("org.mongodb.scala", "mongo-scala-driver_2.13", "5.5.1")
              )),
              spec -> spec.after(actual -> {
                  assertVersion(actual, "mongo-java-driver", "3\\.12\\.14");
                  assertVersion(actual, "mongodb-driver-kotlin-coroutine", "5\\.5\\.1");
                  assertVersion(actual, "mongodb-driver-kotlin-sync", "5\\.5\\.1");
                  assertVersion(actual, "bson-kotlin", "5\\.5\\.1");
                  assertVersion(actual, "mongo-scala-driver_2.13", "5\\.5\\.1");
                  return actual;
              })
            ),
            java("class Application {}")
          )
        );
    }

    @Test
    void doesNotUpgradeMongoDriverOutsideSpringDataMongoDbModules() {
        rewriteRun(
          mavenProject("driver-only",
            pomXml(
              pom("", "", dependency("org.mongodb", "mongodb-driver-sync", "4.11.5"))
            ),
            java("class Application {}")
          )
        );
    }

    private static String pom(String parent, String dependencyManagement, String dependencies) {
        return """
          <project>
              <modelVersion>4.0.0</modelVersion>
              %s
              <groupId>com.example</groupId>
              <artifactId>example</artifactId>
              <version>1.0.0</version>
              %s
              <dependencies>
                  %s
              </dependencies>
          </project>
          """.formatted(parent, dependencyManagement, dependencies);
    }

    private static String bootParent(String version) {
        return """
          <parent>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-parent</artifactId>
              <version>%s</version>
          </parent>
          """.formatted(version);
    }

    private static String mongoDbBom(String version) {
        return """
          <dependencyManagement>
              <dependencies>
                  <dependency>
                      <groupId>org.mongodb</groupId>
                      <artifactId>mongodb-driver-bom</artifactId>
                      <version>%s</version>
                      <type>pom</type>
                      <scope>import</scope>
                  </dependency>
              </dependencies>
          </dependencyManagement>
          """.formatted(version);
    }

    private static String dependency(String groupId, String artifactId, String version) {
        return """
          <dependency>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
          </dependency>
          """.formatted(groupId, artifactId, version);
    }

    private static String versionlessDependency(String groupId, String artifactId) {
        return """
          <dependency>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
          </dependency>
          """.formatted(groupId, artifactId);
    }

    private static void assertVersion(String pom, String artifactId, String versionPattern) {
        assertThat(pom).containsPattern(
          "<artifactId>" + artifactId + "</artifactId>\\s*<version>" + versionPattern + "</version>");
    }

    private static void assertVersionless(String pom, String artifactId) {
        assertThat(pom)
          .contains("<artifactId>" + artifactId + "</artifactId>")
          .doesNotContainPattern("<artifactId>" + artifactId + "</artifactId>\\s*<version>");
    }
}
