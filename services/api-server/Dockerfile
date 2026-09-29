FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        gpac \
        libheif-examples \
        libimage-exiftool-perl \
    && command -v ffmpeg \
    && command -v ffprobe \
    && command -v MP4Box \
    && command -v heif-enc \
    && heif-enc --list-encoders 2>/dev/null | grep -Eq '^- x265 =' \
    && command -v exiftool \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system qingjing \
    && useradd --system --gid qingjing --home-dir /app --create-home qingjing
WORKDIR /app
COPY target/wallpaper-api-server-*.jar /app/application.jar
RUN mkdir -p /app/.runtime/storage && chown -R qingjing:qingjing /app
USER qingjing
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
