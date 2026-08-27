#!/usr/bin/env python3
"""
yt-dlp API Server for DroidRelay
Run this on a machine with yt-dlp installed and a good IP (not carrier NAT).

Usage:
  pip install fastapi uvicorn yt-dlp
  python3 ytdlp_server.py --host 0.0.0.0 --port 8080 --api-key YOUR_KEY

API Endpoints:
  GET  /health                    - Health check
  POST /analyze                   - Analyze YouTube URL, return formats
    Body: {"url": "https://youtube.com/watch?v=..."}
    Response: {"title": "...", "formats": [...], "url": "..."}
  POST /download                  - Get direct download URL for a format
    Body: {"url": "https://youtube.com/watch?v=...", "format": "bestvideo+bestaudio/best"}
    Response: {"url": "https://..."}
"""

import os
import json
import subprocess
import asyncio
from typing import Optional
from fastapi import FastAPI, HTTPException, Header, Request
from pydantic import BaseModel, HttpUrl
import uvicorn

app = FastAPI(title="yt-dlp API Server")

API_KEY = os.environ.get("YTDLP_API_KEY", "")

class AnalyzeRequest(BaseModel):
    url: HttpUrl

class DownloadRequest(BaseModel):
    url: HttpUrl
    format: str = "bestvideo+bestaudio/best"

def verify_api_key(x_api_key: Optional[str] = Header(None)):
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")

@app.get("/health")
async def health():
    return {"status": "ok", "yt_dlp_version": get_yt_dlp_version()}

def get_yt_dlp_version() -> str:
    try:
        result = subprocess.run(["yt-dlp", "--version"], capture_output=True, text=True, timeout=10)
        return result.stdout.strip()
    except Exception:
        return "unknown"

def run_yt_dlp(args: list) -> dict:
    """Run yt-dlp with given args and return parsed JSON output."""
    cmd = ["yt-dlp", "--no-warnings", "--dump-json"] + args
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            raise RuntimeError(f"yt-dlp failed: {result.stderr}")
        # yt-dlp outputs one JSON per line for playlists; take first line
        output = result.stdout.strip()
        if not output:
            raise RuntimeError("No output from yt-dlp")
        # Handle multiple lines (playlist) - take first
        first_line = output.split('\n')[0]
        return json.loads(first_line)
    except subprocess.TimeoutExpired:
        raise RuntimeError("yt-dlp timeout")
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse yt-dlp output: {e}")
    except Exception as e:
        raise RuntimeError(f"yt-dlp error: {e}")

@app.post("/analyze")
async def analyze(request: AnalyzeRequest, x_api_key: Optional[str] = Header(None)):
    verify_api_key(x_api_key)
    try:
        info = run_yt_dlp([str(request.url)])
        formats = []
        for f in info.get("formats", []):
            if f.get("vcodec") != "none" or f.get("acodec") != "none":
                formats.append({
                    "id": f.get("format_id"),
                    "ext": f.get("ext"),
                    "resolution": f.get("resolution"),
                    "fps": f.get("fps"),
                    "vcodec": f.get("vcodec"),
                    "acodec": f.get("acodec"),
                    "filesize": f.get("filesize"),
                    "tbr": f.get("tbr"),
                    "format_note": f.get("format_note"),
                    "protocol": f.get("protocol"),
                })
        return {
            "title": info.get("title"),
            "url": info.get("webpage_url") or str(request.url),
            "duration": info.get("duration"),
            "thumbnail": info.get("thumbnail"),
            "formats": formats,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/download")
async def download(request: DownloadRequest, x_api_key: Optional[str] = Header(None)):
    verify_api_key(x_api_key)
    try:
        # Get the direct URL for the specified format
        cmd = ["yt-dlp", "--no-warnings", "-f", request.format, "-g", str(request.url)]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            raise RuntimeError(f"yt-dlp failed: {result.stderr}")
        urls = [line.strip() for line in result.stdout.strip().split('\n') if line.strip()]
        if not urls:
            raise RuntimeError("No URLs returned")
        return {"urls": urls, "format": request.format}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="yt-dlp API Server for DroidRelay")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind")
    parser.add_argument("--api-key", default="", help="API key for authentication")
    args = parser.parse_args()
    
    if args.api_key:
        os.environ["YTDLP_API_KEY"] = args.api_key
        print(f"API key set: {args.api_key}")
    
    print(f"Starting yt-dlp server on {args.host}:{args.port}")
    print(f"yt-dlp version: {get_yt_dlp_version()}")
    
    uvicorn.run(app, host=args.host, port=args.port)