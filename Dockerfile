# =========================
# Build stage (Maven + JDK17)
# =========================
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

# 依存解決をキャッシュさせるため pom.xml を先にコピー
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# ソースコードをコピーしてビルド
COPY src ./src
RUN mvn -q -DskipTests package

# =========================
# Runtime stage (JRE17)
# =========================
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar /app/
RUN ls -la /app
CMD ["sh", "-c", "java -jar /app/fetch-trends.jar"]