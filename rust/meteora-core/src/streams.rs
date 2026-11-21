//! Stream decisions, ported from the Kotlin `InnerTubeStreams` and `WebMusicStreams`: which audio
//! format to play, what the player page and script say, and how a ciphered format becomes a URL.
//!
//! Every function returns JSON text: `{"ok":true,...}` or `{"ok":false,"error":"..."}`.
//! "Not found" is `ok:true` with a null value; errors never contain URLs or challenge values.

use serde::Deserialize;
use serde_json::{Map, Value as Json, json};

pub(crate) fn ok(v: Json) -> String {
    let mut m = Map::new();
    m.insert("ok".into(), Json::Bool(true));
    if let Json::Object(fields) = v {
        m.extend(fields);
    }
    Json::Object(m).to_string()
}

pub(crate) fn err(message: &str) -> String {
    json!({ "ok": false, "error": message }).to_string()
}

/// Opus sounds better per bit, so it wins near-ties; a much higher-bitrate AAC still wins.
const OPUS_WEIGHT: f64 = 1.3;
const DEFAULT_EXPIRES_SEC: i64 = 6 * 3600;

/// A JSON primitive's text, as kotlinx's `contentOrNull` gives it: numbers and booleans too.
fn text(v: Option<&Json>) -> Option<String> {
    match v? {
        Json::String(s) => Some(s.clone()),
        Json::Number(n) => Some(n.to_string()),
        Json::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn obj<'a>(v: &'a Json, key: &str) -> Option<&'a Map<String, Json>> {
    v.as_object()?.get(key)?.as_object()
}

fn field(f: &Map<String, Json>, key: &str) -> Option<String> {
    text(f.get(key))
}

/// Average-bitrate ceiling per tier; `None` for an unknown tier name.
fn ceiling(tier: &str) -> Option<i32> {
    match tier {
        "Low" => Some(56_000),
        "Normal" => Some(160_000),
        "High" => Some(i32::MAX),
        _ => None,
    }
}

/// `/player` response → `status`, `reason` and the picked audio `format` (null when nothing is
/// playable). With `allow_cipher`, formats that only have a `signatureCipher` count too (the
/// web music client); the caller then has to decipher `cipher` into a URL.
pub fn pick_audio(player: &str, tier: &str, allow_cipher: bool) -> String {
    let Some(max) = ceiling(tier) else { return err("unknown quality tier") };
    let Ok(root) = serde_json::from_str::<Json>(player) else { return err("player response is not JSON") };
    let ps = obj(&root, "playabilityStatus");
    let status = ps.and_then(|p| field(p, "status"));
    let reason = ps.and_then(|p| field(p, "reason"));
    let format = if status.as_deref() == Some("OK") { pick(&root, max, allow_cipher) } else { None };
    ok(json!({ "status": status, "reason": reason, "format": format }))
}

fn pick(root: &Json, max: i32, allow_cipher: bool) -> Option<Json> {
    let sd = obj(root, "streamingData")?;
    let all: Vec<&Map<String, Json>> = sd
        .get("adaptiveFormats")
        .and_then(Json::as_array)
        .map(|a| a.iter().filter_map(Json::as_object).collect())
        .unwrap_or_default();
    let all: Vec<&Map<String, Json>> = all
        .into_iter()
        .filter(|f| field(f, "mimeType").is_some_and(|m| m.starts_with("audio/")))
        .filter(|f| field(f, "url").is_some() || (allow_cipher && field(f, "signatureCipher").is_some()))
        .collect();
    // Loudness-compressed and dubbed tracks only when nothing else is there.
    let mut formats: Vec<&Map<String, Json>> = all
        .iter()
        .copied()
        .filter(|f| {
            field(f, "isDrc").as_deref() != Some("true")
                && f.get("audioTrack").and_then(Json::as_object).and_then(|t| field(t, "audioIsDefault")).as_deref() != Some("false")
        })
        .collect();
    if formats.is_empty() {
        formats = all;
    }
    let bitrate = |f: &Map<String, Json>| -> i32 {
        field(f, "averageBitrate")
            .and_then(|s| s.parse::<i32>().ok())
            .or_else(|| field(f, "bitrate").and_then(|s| s.parse::<i32>().ok()))
            .unwrap_or(0)
    };
    let score = |f: &Map<String, Json>| -> f64 {
        let opus = field(f, "mimeType").is_some_and(|m| m.contains("opus"));
        bitrate(f) as f64 * if opus { OPUS_WEIGHT } else { 1.0 }
    };
    // Strict comparisons: the first of equal formats wins, as with Kotlin's maxByOrNull / minByOrNull.
    let mut best: Option<(&Map<String, Json>, f64)> = None;
    for f in formats.iter().copied().filter(|f| bitrate(f) <= max) {
        let s = score(f);
        if best.is_none_or(|(_, b)| s > b) {
            best = Some((f, s));
        }
    }
    let best = match best {
        Some((f, _)) => f,
        None => {
            let mut low: Option<(&Map<String, Json>, i32)> = None;
            for f in formats.iter().copied() {
                let b = bitrate(f);
                if low.is_none_or(|(_, l)| b < l) {
                    low = Some((f, b));
                }
            }
            low?.0
        }
    };
    let url = field(best, "url");
    let cipher = if url.is_none() { field(best, "signatureCipher") } else { None };
    Some(json!({
        "itag": field(best, "itag").and_then(|s| s.parse::<i32>().ok()).unwrap_or(0),
        "bitrate": bitrate(best),
        "url": url,
        "cipher": cipher,
        "content_length": field(best, "contentLength").and_then(|s| s.parse::<i64>().ok()).filter(|&n| n > 0),
        "expires_in_sec": field(sd, "expiresInSeconds").and_then(|s| s.parse::<i64>().ok()).unwrap_or(DEFAULT_EXPIRES_SEC),
    }))
}

/// `sw.js_data` body (`)]}'` + JSON array) → `visitor_data`, the first `Cg…` string in it.
pub fn parse_visitor_data(body: &str) -> String {
    ok(json!({ "visitor_data": visitor_data(body) }))
}

fn visitor_data(body: &str) -> Option<String> {
    let start = body.find('[')?;
    let root: Json = serde_json::from_str(&body[start..]).ok()?;
    let at = [0usize, 2, 0, 0, 13].iter().try_fold(&root, |v, &i| v.as_array()?.get(i));
    if let Some(s) = text(at) {
        return Some(s);
    }
    fn walk(e: &Json) -> Option<String> {
        match e {
            Json::Array(a) => a.iter().find_map(walk),
            Json::Object(o) => o.values().find_map(walk),
            other => text(Some(other)).filter(|s| s.starts_with("Cg") && (20..=64).contains(&s.encode_utf16().count())),
        }
    }
    walk(&root)
}

/// The `music.youtube.com` page → `page`: `js_url`, `player_id`, and `client_version` (null when
/// the page has none; the caller has its own fallback). `page` is null without a usable player.
pub fn parse_player_page(html: &str) -> String {
    let page = js_url(html).and_then(|js_url| {
        let id = js_url["/s/player/".len()..].split('/').next().unwrap_or("");
        // Also the file name on disk, so nothing that could leave the folder.
        let safe = !id.is_empty() && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
        safe.then(|| json!({ "js_url": js_url, "player_id": id, "client_version": client_version(html) }))
    });
    ok(json!({ "page": page }))
}

/// `"jsUrl":"(/s/player/[^"]+?/base\.js)"`, first match.
fn js_url(html: &str) -> Option<&str> {
    const KEY: &str = "\"jsUrl\":\"";
    const PREFIX: &str = "/s/player/";
    const TAIL: &str = "/base.js";
    let mut from = 0;
    while let Some(i) = html[from..].find(KEY) {
        let j = from + i + KEY.len();
        from = from + i + 1;
        let rest = &html[j..];
        if !rest.starts_with(PREFIX) {
            continue;
        }
        // [^"] cannot cross a quote, so the match ends at the first quote after the prefix.
        let Some(q) = rest[PREFIX.len()..].find('"').map(|q| q + PREFIX.len()) else { continue };
        if q >= PREFIX.len() + 1 + TAIL.len() && rest[..q].ends_with(TAIL) {
            return Some(&rest[..q]);
        }
    }
    None
}

/// `"INNERTUBE_CLIENT_VERSION":"([0-9.]+)"`, first match.
fn client_version(html: &str) -> Option<&str> {
    const KEY: &str = "\"INNERTUBE_CLIENT_VERSION\":\"";
    let mut from = 0;
    while let Some(i) = html[from..].find(KEY) {
        let j = from + i + KEY.len();
        from = from + i + 1;
        let rest = &html[j..];
        let n = rest.bytes().take_while(|b| b.is_ascii_digit() || *b == b'.').count();
        if n > 0 && rest.as_bytes().get(n) == Some(&b'"') {
            return Some(&rest[..n]);
        }
    }
    None
}

/// `base.js` → `sts`, the signature timestamp: `(?:signatureTimestamp|sts)\s*:\s*(\d{5})`, first match.
pub fn signature_timestamp(js: &str) -> String {
    ok(json!({ "sts": sts(js) }))
}

fn sts(js: &str) -> Option<i32> {
    // Java's \s, ASCII only.
    fn ws(b: u8) -> bool {
        matches!(b, b' ' | b'\t' | b'\n' | 0x0B | 0x0C | b'\r')
    }
    let b = js.as_bytes();
    for i in 0..b.len() {
        if b[i] != b's' {
            continue;
        }
        let rest = &b[i..];
        let mut k = if rest.starts_with(b"signatureTimestamp") {
            18
        } else if rest.starts_with(b"sts") {
            3
        } else {
            continue;
        };
        while k < rest.len() && ws(rest[k]) {
            k += 1;
        }
        if rest.get(k) != Some(&b':') {
            continue;
        }
        k += 1;
        while k < rest.len() && ws(rest[k]) {
            k += 1;
        }
        if rest.len() >= k + 5 && rest[k..k + 5].iter().all(u8::is_ascii_digit) {
            return std::str::from_utf8(&rest[k..k + 5]).ok()?.parse().ok();
        }
    }
    None
}

/// A picked format as the caller hands it back: `url` (empty or null when ciphered) and `cipher`.
#[derive(Deserialize)]
struct Format {
    #[serde(default)]
    itag: i64,
    url: Option<String>,
    cipher: Option<String>,
}

/// A `signatureCipher` query string: `s` scrambled signature, `sp` the parameter it goes in, `url` the base URL.
struct Cipher {
    s: String,
    sp: String,
    url: String,
}

fn parse_cipher(cipher: &str) -> Option<Cipher> {
    let q = parse_query(cipher);
    let s = lookup(&q, "s")?;
    let url = lookup(&q, "url")?;
    let sp = lookup(&q, "sp").filter(|s| !s.is_empty()).unwrap_or_else(|| "signature".into());
    Some(Cipher { s, sp, url })
}

/// The cipher (if any), the base URL and its `n` value (if any).
fn plan(format: &str) -> Result<(Option<Cipher>, String, Option<String>), String> {
    let f: Format = serde_json::from_str(format).map_err(|_| "bad format JSON".to_string())?;
    let cipher = match &f.cipher {
        Some(c) => Some(parse_cipher(c).ok_or_else(|| format!("bad signatureCipher for itag {}", f.itag))?),
        None => None,
    };
    let base = match &cipher {
        Some(c) => c.url.clone(),
        None => f.url.clone().filter(|u| !u.is_empty()).ok_or_else(|| format!("no URL for itag {}", f.itag))?,
    };
    let n = query_param(&base, "n");
    Ok((cipher, base, n))
}

/// A picked format → what the solver has to answer: `s` (when ciphered) and `n` (when the URL has one).
pub fn stream_challenges(format: &str) -> String {
    match plan(format) {
        Ok((cipher, _, n)) => ok(json!({ "s": cipher.map(|c| c.s), "n": n })),
        Err(e) => err(&e),
    }
}

/// The playable `url` for a picked format: the solved signature added (when ciphered), and the
/// `n` parameter replaced by its solved value.
pub fn build_stream_url(format: &str, solved_s: Option<&str>, solved_n: Option<&str>) -> String {
    let (cipher, base, n) = match plan(format) {
        Ok(p) => p,
        Err(e) => return err(&e),
    };
    let mut url = base;
    if let Some(c) = cipher {
        let Some(s) = solved_s else { return err("signature not solved") };
        url.push(if url.contains('?') { '&' } else { '?' });
        url.push_str(&format!("{}={}", c.sp, encode(s)));
    }
    if n.is_some() {
        let Some(solved) = solved_n else { return err("n not solved") };
        url = replace_query_param(&url, "n", solved);
    }
    ok(json!({ "url": url }))
}

pub(crate) fn query_param(url: &str, name: &str) -> Option<String> {
    let query = url.split_once('?').map(|(_, q)| q).unwrap_or("");
    lookup(&parse_query(query), name)
}

pub(crate) fn replace_query_param(url: &str, name: &str, value: &str) -> String {
    let Some((head, query)) = url.split_once('?') else { return url.to_string() };
    let query: Vec<String> = query
        .split('&')
        .map(|part| {
            let key = part.split_once('=').map(|(k, _)| k).unwrap_or(part);
            if decode(key) == name { format!("{name}={}", encode(value)) } else { part.to_string() }
        })
        .collect();
    format!("{head}?{}", query.join("&"))
}

fn parse_query(query: &str) -> Vec<(String, String)> {
    query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|p| match p.split_once('=') {
            Some((k, v)) => (decode(k), decode(v)),
            None => (decode(p), String::new()),
        })
        .collect()
}

/// The last value for `name`: later keys overwrite earlier ones, as with Kotlin's `associate`.
fn lookup(q: &[(String, String)], name: &str) -> Option<String> {
    q.iter().rev().find(|(k, _)| k == name).map(|(_, v)| v.clone())
}

/// `java.net.URLEncoder.encode(s, "UTF-8")`.
pub(crate) fn encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// `java.net.URLDecoder.decode(s, "UTF-8")`, or `s` itself where Java would throw.
pub(crate) fn decode(s: &str) -> String {
    try_decode(s).unwrap_or_else(|| s.to_string())
}

fn try_decode(s: &str) -> Option<String> {
    // Integer.parseInt(.., 16) on two chars: a sign is allowed, a negative value is not.
    fn hex(a: char, b: char) -> Option<u8> {
        let d = |c: char| c.to_digit(16);
        match a {
            '+' => d(b).map(|v| v as u8),
            '-' => (d(b)? == 0).then_some(0),
            _ => Some((d(a)? * 16 + d(b)?) as u8),
        }
    }
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '+' => {
                out.push(' ');
                i += 1;
            }
            '%' => {
                let mut bytes = Vec::new();
                while i + 2 < chars.len() && chars[i] == '%' {
                    bytes.push(hex(chars[i + 1], chars[i + 2])?);
                    i += 3;
                }
                if i < chars.len() && chars[i] == '%' {
                    return None;
                }
                out.push_str(&String::from_utf8_lossy(&bytes));
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_coding_matches_java() {
        assert_eq!(encode("a=b/c+ ~é"), "a%3Db%2Fc%2B+%7E%C3%A9");
        assert_eq!(encode("AZaz09.-*_"), "AZaz09.-*_");
        assert_eq!(decode("a%3Db%2Fc%2B+x"), "a=b/c+ x");
        assert_eq!(decode("%C3%A9"), "é");
        // Malformed escapes: Java throws, and the Kotlin kept the text as it was.
        assert_eq!(decode("50%"), "50%");
        assert_eq!(decode("%zz"), "%zz");
        assert_eq!(decode("a%2"), "a%2");
    }

    #[test]
    fn query_helpers() {
        assert_eq!(query_param("https://r/v?a=1&n=p%2Bq", "n").as_deref(), Some("p+q"));
        assert_eq!(query_param("https://r/v?a=1", "n"), None);
        assert_eq!(query_param("https://r/v", "n"), None);
        assert_eq!(query_param("https://r/v?n=1&n=2", "n").as_deref(), Some("2"));
        assert_eq!(replace_query_param("https://r/v?a=1&n=y&nn=2", "n", "z"), "https://r/v?a=1&n=z&nn=2");
        assert_eq!(replace_query_param("https://r/v", "n", "z"), "https://r/v");
        assert_eq!(replace_query_param("https://r/v?n=1&&n=2", "n", "a b"), "https://r/v?n=a+b&&n=a+b");
    }
}
