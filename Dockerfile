# Java-API «Охотника на смурфиков».
#
# Сборка в два этапа: Maven с зависимостями остаётся в builder-слое, а в
# финальный образ попадают только классы и jar-файлы. Итог — около 200 МБ
# вместо гигабайта с JDK и Maven.

# ---------- Этап 1: сборка ----------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Сначала только pom.xml: пока он не менялся, слой с зависимостями берётся
# из кеша, и правка одного .java не тянет всё заново.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:resolve

COPY src ./src

# package собирает классы, copy-dependencies кладёт gson и jgrapht в target/lib.
RUN mvn -B -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib

# ---------- Этап 2: запуск ----------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /build/target/classes ./classes
COPY --from=build /build/target/lib ./lib

# Без этого русский текст в логах превращается в "?????".
ENV LANG=C.UTF-8

EXPOSE 8080

# "-cp classes:lib/*" вместо jar с манифестом: не нужно ничего дописывать в pom.
# Кавычки вокруг -D... обязательны: без них некоторые оболочки рвут аргумент по "=".
ENTRYPOINT ["java", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-cp", "classes:lib/*", "com.papasmurf.web.ApiServer"]
