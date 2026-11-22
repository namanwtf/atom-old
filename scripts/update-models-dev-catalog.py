#!/usr/bin/env python3
"""Update the models.dev offline model catalog bundled in the APK."""

import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import urllib.request


CATALOG_URL = "https://models.dev/api.json"
MAX_CATALOG_BYTES = 16 * 1024 * 1024
SNAPSHOT = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/assets/catalog/models-dev-api.json.gzip"
)


def download_catalog() -> bytes:
    request = urllib.request.Request(
        CATALOG_URL,
        headers={
            "User-Agent": "Eta-Catalog-Updater (+https://github.com/Mangi-11/Eta)",
            "Accept": "application/json",
            "Accept-Encoding": "identity",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.geturl() != CATALOG_URL:
            raise ValueError("model catalog redirected to an unexpected URL")
        if response.headers.get_content_type() != "application/json":
            raise ValueError("model catalog is not a JSON response")
        declared_size = response.headers.get("Content-Length")
        if declared_size is not None and int(declared_size) > MAX_CATALOG_BYTES:
            raise ValueError("model catalog exceeds the size limit")
        data = response.read(MAX_CATALOG_BYTES + 1)

    if len(data) > MAX_CATALOG_BYTES:
        raise ValueError("model catalog exceeds the size limit")
    catalog = json.loads(data.decode("utf-8"))
    if not isinstance(catalog, dict) or not catalog:
        raise ValueError("model catalog top-level structure is invalid")
    if not all(
        isinstance(provider_id, str)
        and isinstance(provider, dict)
        and isinstance(provider.get("models"), dict)
        for provider_id, provider in catalog.items()
    ):
        raise ValueError("model catalog provider structure is invalid")
    return data


def gzip_snapshot(data: bytes) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0, compresslevel=9) as stream:
        stream.write(data)
    return output.getvalue()


def main() -> None:
    data = download_catalog()
    compressed = gzip_snapshot(data)
    SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
    if SNAPSHOT.exists() and SNAPSHOT.read_bytes() == compressed:
        print(f"catalog unchanged: {SNAPSHOT} ({len(compressed)} bytes)")
        return

    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=SNAPSHOT.parent, prefix=".models-dev-", suffix=".tmp", delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(compressed)
        os.chmod(temporary_path, 0o644)
        os.replace(temporary_path, SNAPSHOT)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    digest = hashlib.sha256(data).hexdigest()
    print(f"updated {SNAPSHOT}: raw {len(data)} bytes, gzip {len(compressed)} bytes, SHA-256 {digest}")


if __name__ == "__main__":
    main()
