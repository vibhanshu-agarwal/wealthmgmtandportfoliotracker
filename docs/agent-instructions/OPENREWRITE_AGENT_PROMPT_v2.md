# 🤖 Coding Agent Instructions: OpenRewrite Automated Refactoring

## 🎯 Context & Objective
You are an Expert Java/Spring Architect. Your task is to integrate the **OpenRewrite Gradle Plugin** into our Spring Boot Modulith project, execute the automated Spring best-practices refactoring recipes, and resolve any compilation or architectural test failures that arise from the automated changes.

**Tech Stack Context:** We are using **Java 25** and **Spring Boot 4+**.

## 🛠️ Execution Plan

Execute the following steps sequentially. Do not proceed to the next step until the current step is fully complete and verified.

### Step 1: Configure the OpenRewrite Gradle Plugin
Modify the root `build.gradle` to include the OpenRewrite plugin and the latest Spring recipes.

1. Add the plugin to the `plugins` block using the latest stable 6.x version:
   `id("org.openrewrite.rewrite") version("latest.release")`
2. Add the `rewrite` configuration block to activate the standard Spring Boot 4 cleanup and migration recipes:
   ```gradle
   rewrite {
       activeRecipe(
           // NOTE to Agent: Ensure this uses the Boot 4 / latest recipe available in the OpenRewrite catalog
           "org.openrewrite.java.spring.boot4.SpringBootBestPractices", 
           "org.openrewrite.java.format.AutoFormat"
       )
   }