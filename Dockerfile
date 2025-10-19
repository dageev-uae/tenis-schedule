# Multi-stage build для оптимизации размера образа
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Копируем файлы сборки
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Копируем исходный код
COPY src ./src

# Собираем приложение с Shadow JAR (создает fat JAR с манифестом)
RUN gradle shadowJar --no-daemon -x test

# Финальный образ
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Копируем собранный fat JAR из builder stage
COPY --from=builder /app/build/libs/app.jar app.jar

# Переменные окружения (будут переопределены в Railway)
ENV TELEGRAM_BOT_TOKEN=""
ENV COURT_USERNAME=""
ENV COURT_PASSWORD=""
ENV DATABASE_PATH="/data/tennis_bot.db"

# Создаем директорию для базы данных
RUN mkdir -p /data

# Запускаем приложение
CMD ["java", "-jar", "app.jar"]
