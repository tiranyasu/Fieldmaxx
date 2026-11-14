# The Fieldmaxx server image. Built from the repository root (docker-compose.yml does that):
#   docker build -f server/Dockerfile .
# Stage 1 builds the server's fat jar with the Gradle wrapper; stage 2 is a plain Java 21 runtime with that one jar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# The wrapper on its own first, so the Gradle download stays cached while only the code changes.
COPY gradlew ./
COPY gradle/wrapper gradle/wrapper
RUN ./gradlew --no-daemon --version
COPY . .
# --configure-on-demand configures only the server and the modules it uses, so the Android app and its SDK aren't
# needed here. In-process Kotlin and a 1 GB heap keep the build within a small server's memory.
RUN ./gradlew --no-daemon --configure-on-demand -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.jvmargs=-Xmx1g :server:buildFatJar

FROM eclipse-temurin:21-jre
COPY --from=build /src/server/build/libs/fieldmaxx-server-all.jar /app/fieldmaxx.jar
# Everything the server keeps: the database, photo backups, thumbnails, job files and published APKs.
ENV FMX_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
# Half the memory for the Java heap; on running out of it, exit so Docker restarts the server instead of limping on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/fieldmaxx.jar"]
