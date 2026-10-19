# swissdata sidecar. Build from sidecar/:  docker build -t swissdata .
# Wheels only (rasterio/fiona/shapely ship their own GDAL/GEOS/PROJ); no system GDAL.
FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.12.18 /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_NO_BUILD=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY pyproject.toml uv.lock ./
# dependencies only (all from wheels); the project itself runs from /app, so nothing is built from source
RUN uv sync --frozen --no-dev --no-install-project
COPY swissdata ./swissdata

ENV PATH=/app/.venv/bin:$PATH \
    PYTHONPATH=/app \
    SWISSDATA_STORE=/store \
    SWISSDATA_RAW=/raw \
    SWISSDATA_SCRATCH=/raw/scratch \
    SWISSDATA_KEEP_RAW=0 \
    SWISSDATA_DEV_BBOX=0 \
    SWISSDATA_HOST=0.0.0.0 \
    SWISSDATA_PORT=8080

RUN useradd --system --uid 10001 swissdata && mkdir -p /store /raw && chown swissdata /store /raw
USER swissdata
VOLUME ["/store", "/raw"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4).status == 200 else 1)"

CMD ["python", "-m", "swissdata.server"]
