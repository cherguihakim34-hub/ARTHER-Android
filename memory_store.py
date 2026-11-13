import os
import logging
from urllib.parse import urlparse, urlunparse

import asyncpg

logger = logging.getLogger("ARTHER.memory")

_pool = None


def get_database_url():
    url = os.getenv("DATABASE_URL", "").strip()
    if not url:
        return None

    # Render may provide postgres://; asyncpg expects postgresql://
    if url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://"):]

    return url


async def init_memory():
    global _pool

    database_url = get_database_url()
    if not database_url:
        logger.warning("DATABASE_URL is not configured.")
        return False

    try:
        _pool = await asyncpg.create_pool(
            database_url,
            min_size=1,
            max_size=5,
            command_timeout=20
        )

        async with _pool.acquire() as conn:
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS arther_messages (
                    id BIGSERIAL PRIMARY KEY,
                    session_id VARCHAR(80) NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
            """)

            await conn.execute("""
                CREATE INDEX IF NOT EXISTS
                idx_arther_messages_session
                ON arther_messages(session_id, id);
            """)

        logger.info("ARTHER persistent memory is ready.")
        return True

    except Exception:
        logger.exception("Could not initialize ARTHER memory.")
        _pool = None
        return False


async def get_history(session_id: str, limit: int = 12):
    if _pool is None:
        return []

    try:
        async with _pool.acquire() as conn:
            rows = await conn.fetch("""
                SELECT role, content
                FROM (
                    SELECT id, role, content
                    FROM arther_messages
                    WHERE session_id = $1
                    ORDER BY id DESC
                    LIMIT $2
                ) recent
                ORDER BY id ASC;
            """, session_id, limit)

        return [
            {"role": row["role"], "content": row["content"]}
            for row in rows
        ]

    except Exception:
        logger.exception("Memory history retrieval failed.")
        return []


async def save_message(session_id: str, role: str, content: str):
    if _pool is None or not content:
        return False

    if role not in ("user", "assistant"):
        return False

    try:
        async with _pool.acquire() as conn:
            await conn.execute("""
                INSERT INTO arther_messages
                    (session_id, role, content)
                VALUES ($1, $2, $3);
            """, session_id, role, content[:12000])

        return True

    except Exception:
        logger.exception("Memory save failed.")
        return False


async def close_memory():
    global _pool

    if _pool is not None:
        await _pool.close()
        _pool = None