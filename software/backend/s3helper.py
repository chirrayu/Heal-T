try:
    import boto3
    from botocore.exceptions import ClientError
    _has_boto3 = True
except ImportError:
    boto3 = None
    ClientError = Exception
    _has_boto3 = False

import uuid
import os
from config import (
    AWS_ACCESS_KEY_ID,
    AWS_SECRET_ACCESS_KEY,
    AWS_REGION,
    S3_BUCKET_NAME,
    S3_PRESIGNED_EXPIRATION_SECONDS,
    MAX_UPLOAD_PHOTO_BYTES
)

# ── S3 client ─────────────────────────────────────────────────────
s3_client = None
if _has_boto3 and AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY:
    try:
        s3_client = boto3.client(
            "s3",
            aws_access_key_id=AWS_ACCESS_KEY_ID,
            aws_secret_access_key=AWS_SECRET_ACCESS_KEY,
            region_name=AWS_REGION
        )
    except Exception:
        s3_client = None


ALLOWED_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}


def validate_image_bytes(file_bytes: bytes, content_type: str) -> None:
    """
    Validates that the file does not exceed MAX_UPLOAD_PHOTO_BYTES (5MB)
    and verifies binary magic bytes matching the declared MIME type.
    """
    if len(file_bytes) > MAX_UPLOAD_PHOTO_BYTES:
        raise ValueError(f"File size ({len(file_bytes)} bytes) exceeds maximum limit of {MAX_UPLOAD_PHOTO_BYTES} bytes (5MB).")

    if content_type not in ALLOWED_MIME_TYPES:
        raise ValueError(f"Invalid image type '{content_type}'. Allowed types: {', '.join(sorted(ALLOWED_MIME_TYPES))}")

    # Check magic bytes to prevent masquerading / executable uploads
    if content_type == "image/jpeg":
        if not file_bytes.startswith(b"\xff\xd8\xff"):
            raise ValueError("Corrupted or invalid JPEG image header.")
    elif content_type == "image/png":
        if not file_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Corrupted or invalid PNG image header.")
    elif content_type == "image/webp":
        if not (file_bytes.startswith(b"RIFF") and len(file_bytes) >= 12 and file_bytes[8:12] == b"WEBP"):
            raise ValueError("Corrupted or invalid WebP image header.")


def upload_photo(file_bytes: bytes, content_type: str, soldier_id: str) -> str:
    """
    Uploads a photo to S3 after validating size and magic bytes.
    Returns the S3 key (not a public URL — we use presigned URLs).
    """
    validate_image_bytes(file_bytes, content_type)

    if s3_client is None:
        raise Exception("AWS S3 client is not configured on the server.")

    ext_map = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}
    ext = ext_map.get(content_type, "jpg")
    key = f"soldiers/{soldier_id}/photo_{uuid.uuid4().hex[:8]}.{ext}"

    try:
        s3_client.put_object(
            Bucket=S3_BUCKET_NAME,
            Key=key,
            Body=file_bytes,
            ContentType=content_type
        )
        return key
    except ClientError as e:
        raise Exception(f"S3 upload failed: {str(e)}")


def delete_photo(s3_key: str):
    """Deletes a photo from S3 by its key."""
    if s3_client is None:
        return
    try:
        s3_client.delete_object(Bucket=S3_BUCKET_NAME, Key=s3_key)
    except ClientError as e:
        raise Exception(f"S3 delete failed: {str(e)}")


def get_presigned_url(s3_key: str, expires_in: int = S3_PRESIGNED_EXPIRATION_SECONDS) -> str:
    """
    Generates a temporary URL to access the photo.
    expires_in = seconds until URL expires (default 900s / 15 minutes).
    The Android app uses this URL to display the image.
    """
    if s3_client is None:
        return f"https://mock-storage.local/{s3_key}"

    try:
        url = s3_client.generate_presigned_url(
            "get_object",
            Params={
                "Bucket": S3_BUCKET_NAME,
                "Key": s3_key
            },
            ExpiresIn=expires_in
        )
        return url
    except ClientError as e:
        raise Exception(f"Failed to generate presigned URL: {str(e)}")


def photo_exists(s3_key: str) -> bool:
    """Check if a photo exists in S3."""
    if s3_client is None:
        return False
    try:
        s3_client.head_object(Bucket=S3_BUCKET_NAME, Key=s3_key)
        return True
    except ClientError:
        return False