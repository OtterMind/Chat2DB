# Windows updater helper

This module builds the elevated helper copied to
`jpackage/input/win/updater.jar` by the Community desktop packaging script.

The current helper is approximately 2.3 MB instead of the previous 14 MB
because it is a standalone shaded JAR containing only the updater code and its
Jackson runtime dependencies. The previous artifact was built as a Spring Boot
executable JAR and bundled the Spring Boot launcher, Spring Framework, logging,
SnakeYAML, Lombok, and other server-side runtime libraries that the updater
does not use.

Build and test it with:

```bash
mvn -B -f jpackage/updater/pom.xml clean package
```

The packaging script rebuilds this module and copies the reproducible shaded
artifact into the versioned Windows packaging resource. Do not replace it with
the Community server JAR or another Spring Boot artifact.
