FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        gpac \
        libheif-examples \
        libimage-exiftool-perl \
        curl \
    && command -v ffmpeg \
    && command -v ffprobe \
    && command -v MP4Box \
    && command -v heif-enc \
    && heif-enc --list-encoders 2>/dev/null | grep -Eq '^- x265 =' \
    && command -v exiftool \
    && rm -rf /var/lib/apt/lists/*
# Jammy's ExifTool 12.40 cannot write the Apple Live Photo content identifiers.
RUN curl --fail --silent --show-error --location \
        https://cpan.metacpan.org/authors/id/E/EX/EXIFTOOL/Image-ExifTool-13.55.tar.gz \
        -o /tmp/exiftool.tar.gz \
    && echo '5f4c81d34ad406538c2871ad72dbfceb5d9b412b2f16cbbeb4d712d270846667  /tmp/exiftool.tar.gz' | sha256sum -c - \
    && mkdir -p /opt/exiftool \
    && tar -xzf /tmp/exiftool.tar.gz -C /opt/exiftool --strip-components=1 \
    && /opt/exiftool/exiftool -ver | grep -Fx '13.55' \
    && rm /tmp/exiftool.tar.gz
ENV QJ_EXIFTOOL=/opt/exiftool/exiftool
RUN groupadd --system qingjing \
    && useradd --system --gid qingjing --home-dir /app --create-home qingjing
WORKDIR /app
COPY target/wallpaper-api-server-*.jar /app/application.jar
RUN mkdir -p /app/.runtime/storage && chown -R qingjing:qingjing /app
USER qingjing
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
