# syntax=docker/dockerfile:1
# Ubuntu 22.04 base → glibc 2.35，满足 DJL tokenizers 0.36 对 GLIBC_2.32 的要求，
# 让 BgeM3OnnxEmbeddingProvider 在容器里能加载 libtokenizers.so（宿主机 glibc 2.31 不行）。
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 构建期产物：./gradlew :backend:bootJar
COPY backend/build/libs/feedback-platform-backend.jar /app/app.jar

# BGE-M3 模型（onnx + tokenizer.json）以卷方式挂载，不打进镜像（约 2GB）。
# DJL 的 tokenizers native 库缓存挂卷，避免每次启动重下。
EXPOSE 18080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
