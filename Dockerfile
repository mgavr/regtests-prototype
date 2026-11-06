# Stage 1: build everything and run every unit test. A failing test fails the image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY regtests-capture-starter regtests-capture-starter
COPY demo-service demo-service
COPY fake-partner fake-partner
RUN mvn -B -ntp verify

# Stages 2 and 3: the Java runtime and Python, taken from their official images.
FROM eclipse-temurin:17-jre AS jre

FROM python:3.12-slim-bookworm AS python
COPY tests/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

# Final image: the official MariaDB image, so the database is a real one, with the rest added.
FROM mariadb:11.4
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=python /usr/local /usr/local
RUN ldconfig && mkdir -p /app/lib /app/db /data
ENV PATH=/opt/java/openjdk/bin:$PATH \
    JAVA_HOME=/opt/java/openjdk \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY --from=build /src/demo-service/target/demo-service.jar /src/fake-partner/target/fake-partner.jar lib/
COPY demo-service/db/seed.sql db/
COPY console console
COPY tests tests
COPY recordings recordings
# The live page shows the code behind each step, taken from the REG pointers in the source.
COPY regtests-capture-starter/src/main source/regtests-capture-starter/src/main
COPY demo-service/src/main source/demo-service/src/main
COPY tests source/tests
COPY console/stages.py source/console/stages.py
RUN python3 console/make_snippets.py source console/static/snippets.json
# The comparison rules need no service, so a failure here fails the image too.
RUN python3 -m pytest -q -p no:cacheprovider tests/test_compare.py

EXPOSE 8080
ENTRYPOINT ["python3", "/app/console/cli.py"]
CMD ["console"]
