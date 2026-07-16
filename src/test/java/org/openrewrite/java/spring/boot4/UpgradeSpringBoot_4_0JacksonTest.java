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
package org.openrewrite.java.spring.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;

class UpgradeSpringBoot_4_0JacksonTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0");
    }

    @Test
    void preserveSpringBootManagedJacksonVersions() {
        rewriteRun(
          mavenProject("project",
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.7</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>jackson-app</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <properties>
                        <jackson-bom.version>2.20.1</jackson-bom.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.dataformat</groupId>
                            <artifactId>jackson-dataformat-xml</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """,
              spec -> spec.after(actual -> assertThat(actual)
                .describedAs("Spring Boot should continue to manage the migrated Jackson dependencies through its BOM")
                .containsPattern("<version>4\\.0\\.\\d+</version>")
                .containsPattern("<jackson-bom\\.version>3\\.1\\.\\d+</jackson-bom\\.version>")
                .contains("<groupId>tools.jackson.core</groupId>")
                .contains("<groupId>tools.jackson.dataformat</groupId>")
                .doesNotContainPattern("(?s)<groupId>tools\\.jackson\\.core</groupId>\\s*<artifactId>jackson-databind</artifactId>\\s*<version>")
                .doesNotContainPattern("(?s)<groupId>tools\\.jackson\\.dataformat</groupId>\\s*<artifactId>jackson-dataformat-xml</artifactId>\\s*<version>")
                .doesNotContain("tools.jackson:jackson-bom:2.20.1")
                .actual())
            )
          )
        );
    }
}
