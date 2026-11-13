import os
import logging
import asyncpg

logger = logging.getLogger("ARTHER.memory")
_pool = None


def get_database_url():
    url = os.getenv("DATABASE_URL", "").strip()
    if url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://"):]
    return url or None


async def init_memory():
    global _pool
    url = get_database_url()
    if not url:
        logger.warning("DATABASE_URL missing")
        return False

    try:
        _pool = await asyncpg.create_pool(
            url, min_size=1, max_size=5, command_timeout=20
        )
        async with _pool.acquire() as conn:
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS arther_messages (
                    id BIGSERIAL PRIMARY KEY,
                    session_id VARCHAR(80) NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );
                CREATE INDEX IF NOT EXISTS idx_arther_messages_session
                ON arther_messages(session_id, id);
            """)
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS arther_memories (
                    id BIGSERIAL PRIMARY KEY,
                    category VARCHAR(80) NOT NULL DEFAULT 'general',
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW()
                );
                CREATE INDEX IF NOT EXISTS idx_arther_memories_updated
                ON arther_memories(updated_at DESC);
            """)
        logger.info("PostgreSQL memory ready")
        return True
    except Exception:
        logger.exception("Memory initialization failed")
        if _pool:
            await _pool.close()
        _pool = None
        return False


async def get_history(session_id, limit=12):
    if not _pool:
        return []
    try:
        async with _pool.acquire() as conn:
            rows = await conn.fetch("""
                SELECT role, content FROM (
                    SELECT id, role, content
                    FROM arther_messages
                    WHERE session_id=$1
                    ORDER BY id DESC LIMIT $2
                ) recent ORDER BY id ASC
            """, session_id, limit)
        return [{"role": r["role"], "content": r["content"]} for r in rows]
    except Exception:
        logger.exception("History retrieval failed")
        return []


async def save_message(session_id, role, content):
    if not _pool or not content or role not in ("user", "assistant"):
        return False
    try:
        async with _pool.acquire() as conn:
            await conn.execute("""
                INSERT INTO arther_messages(session_id, role, content)
                VALUES($1, $2, $3)
            """, session_id, role, content[:12000])
        return True
    except Exception:
        logger.exception("Message save failed")
        return False


async def save_memory(content, category="general"):
    if not _pool:
        return {"ok": False, "error": "Database unavailable"}

    content = (content or "").strip()
    category = (category or "general").strip()[:80]

    if not content:
        return {"ok": False, "error": "Empty memory"}
    if len(content) > 4000:
        return {"ok": False, "error": "Memory too long"}

    try:
        async with _pool.acquire() as conn:
            old = await conn.fetchrow("""
                SELECT id FROM arther_memories
                WHERE category=$1 AND content=$2
                ORDER BY id DESC LIMIT 1
            """, category, content)

            if old:
                await conn.execute("""
                    UPDATE arther_memories
                    SET updated_at=NOW() WHERE id=$1
                """, old["id"])
                return {"ok": True, "status": "already_saved"}

            row = await conn.fetchrow("""
                INSERT INTO arther_memories(category, content)
                VALUES($1, $2) RETURNING id
            """, category, content)

        return {"ok": True, "status": "saved", "id": row["id"]}
    except Exception:
        logger.exception("Memory save failed")
        return {"ok": False, "error": "Database write failed"}


async def search_memories(query="", limit=10):
    if not _pool:
        return []

    limit = max(1, min(int(limit), 20))
    query = (query or "").strip()

    try:
        async with _pool.acquire() as conn:
            if query:
                rows = await conn.fetch("""
                    SELECT id, category, content
                    FROM arther_memories
                    WHERE content ILIKE $1 OR category ILIKE $1
                    ORDER BY updated_at DESC LIMIT $2
                """, f"%{query[:120]}%", limit)
                if rows:
                    return [dict(r) for r in rows]

            rows = await conn.fetch("""
                SELECT id, category, content
                FROM arther_memories
                ORDER BY updated_at DESC LIMIT $1
            """, limit)
        return [dict(r) for r in rows]
    except Exception:
        logger.exception("Memory search failed")
        return []


async def get_memory_context(limit=12):
    rows = await search_memories("", limit)
    return "\n".join(
        f"- [{r['category']}] {r['content']}" for r in rows
    )[:6000]


async def close_memory():
    global _pool
    if _pool:
        await _pool.close()
        _pool = None