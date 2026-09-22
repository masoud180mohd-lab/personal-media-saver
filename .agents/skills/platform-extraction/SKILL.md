---
name: platform-extraction
description: Playbook for extracting public media from hostile platforms (Instagram, TikTok, future adapters) using OkHttp on Android. Invoke for any adapter or extraction work.
---

# Platform Extraction Playbook

## Request etiquette
- Realistic modern browser User-Agent, consistent per session.
- Timeouts: connect 15-30s, read 30-60s. All work on Dispatchers.IO.
- Respect rate limits: no rapid-fire extraction; on 429 back off aggressively;
  treat CAPTCHA/JS-challenge as DEGRADED — never attempt to brute-force it.
- Follow redirects max 5; reject redirects to private IP ranges.

## Extraction layer order (most resilient first)
1. Static HTML signals: <meta property="og:video">, og:image, og:title, twitter:player.
2. Embedded JSON: <script type="application/ld+json">, hydration blobs
   (TikTok __UNIVERSAL_DATA_FOR_REHYDRATION__, Instagram _sharedData /
   window.__additionalDataLoaded).
3. Undocumented endpoints (?__a=1&__d=dis etc.) — last resort, expect breakage.
4. NewPipe Extractor where supported (YouTube, TikTok, SoundCloud) —
   always prefer the library over hand-rolled parsing.

## Parsing rules
- Targeted string scanning for meta tags; kotlinx.serialization for JSON blobs;
  add Jsoup only if truly necessary.
- Always handle: missing fields, empty client-rendered shells, login walls
  (→ ContentRestricted), deleted content (404 → MediaNotFound).
- Carousels/multi-media: return List<StreamInfo>, preserve order, set mimeType per item.

## Volatility rules
- Resolved download URLs expire within minutes — download promptly after resolution.
- healthCheck(): probe the cheapest signal (one lightweight request);
  DEGRADED on anti-bot responses, BROKEN on structural parse failure.
- When a layer breaks, fall through to the next layer and log which layer succeeded
  (tag MediaSaver:<Platform>).

## Testing
- MockWebServer fixtures: success, 403, 404, 429, empty HTML shell, login-wall HTML.
- Unit-test URL parsers with real-world variants including tracking params (?igshid=, ?si=).
