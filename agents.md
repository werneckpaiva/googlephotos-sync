# Agent Development Guide

This document contains instructions for agents and developers on how to maintain, build, and run this project. **Always use `./gradlew`** for build and test tasks to ensure environment consistency.

## Build Commands

To compile the project and check for errors:
```bash
./gradlew classes
```

To run tests:
```bash
./gradlew test
```

To create the executable Shadow JAR (fat JAR):
```bash
./gradlew shadowJar
```

The output JAR will be located at:  
`build/libs/googlephotos-sync-*-uber.jar`

## Running the Application

After building the `shadowJar`, you can run the application using:
```bash
java -jar build/libs/googlephotos-sync-1.0-SNAPSHOT-uber.jar <baseFolder> [options]
```

## Useful Gradle Tasks

- `./gradlew clean`: Delete the build directory.
- `./gradlew build`: Full build including tests.
- `./gradlew run`: Run the application directly from source (requires arguments configured in `build.gradle` or passed via `--args`).

## Project Structure

- `src/main/java`: Source code.
- `src/main/resources`: Configuration and static resources (like `credentials.json`).
- `src/test/java`: Unit and integration tests.
