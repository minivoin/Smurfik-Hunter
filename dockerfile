# === ЭТАП 1: Сборка проекта через Maven ===
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Копируем только pom.xml сначала (для кэширования зависимостей)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Копируем исходники и собираем JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# === ЭТАП 2: Финальный лёгкий образ ===
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Копируем только собранный JAR из первого этапа
COPY --from=builder /app/target/*.jar app.jar

# Копируем .env файл (если он нужен приложению)
COPY .env .env

# Открываем порт (если у тебя есть HTTP сервер, например SparkJava на 8080)
# Если у тебя просто консольное приложение — эту строку можно убрать
EXPOSE 8080

# Запуск приложения
ENTRYPOINT ["java", "-jar", "app.jar"]