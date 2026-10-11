FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DATA_DIR=/data \
    PORT=8080

WORKDIR /app
COPY server.py /app/server.py

RUN mkdir -p /data \
    && chown -R 10001:10001 /app /data

USER 10001:10001
EXPOSE 8080

CMD ["python", "/app/server.py"]
