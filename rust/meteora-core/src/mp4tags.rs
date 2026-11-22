//! Tags for YouTube's `.m4a` files: title, artist, album, track number and cover, written the way
//! iTunes does (`moov/udta/meta/ilst`), so any music player and Android's media scanner read them.
//!
//! YouTube's audio files are fragmented MP4: `ftyp`, `moov` (no samples, `stco` empty, `mvex`),
//! `sidx`, then `moof`/`mdat` pairs. Checked on a real itag 140 file (2026-11-22): `sidx` v0 with
//! `first_offset` 0 (relative to the end of `sidx`), every `tfhd` with default-base-is-moof and no
//! base data offset, `trun` data offsets relative to their `moof`, no `mfra`. So `moov` can grow and
//! every byte after it stays as it was. [tag_head] refuses a file where that is not so.
//!
//! No I/O: Kotlin passes the first bytes of the file (up to and past `moov`) and its last 16 bytes,
//! and writes the returned head followed by the file from `resume_at` on.

use serde::Deserialize;

#[derive(Deserialize, Default)]
pub struct Tags {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub track: Option<u16>,
    pub track_total: Option<u16>,
    pub year: Option<String>,
}

/// The new file head (everything before `resume_at`, with the tagged `moov`), and where in the
/// original file to continue copying from.
#[derive(Debug)]
pub struct Tagged {
    pub head: Vec<u8>,
    pub resume_at: u64,
}

struct BoxRef {
    start: usize,
    header: usize,
    end: usize,
    kind: [u8; 4],
}

/// The boxes in `b[from..to]`. Stops at a box that runs past `to` (the head may cut one) and says so.
fn boxes(b: &[u8], from: usize, to: usize) -> Result<(Vec<BoxRef>, bool), String> {
    let mut out = Vec::new();
    let mut at = from;
    while at + 8 <= to {
        let size32 = u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as u64;
        let kind: [u8; 4] = b[at + 4..at + 8].try_into().unwrap();
        let (size, header) = match size32 {
            0 => return Err("box with size 0".into()),
            1 => {
                if at + 16 > to {
                    return Ok((out, true));
                }
                (u64::from_be_bytes(b[at + 8..at + 16].try_into().unwrap()), 16)
            }
            n => (n, 8),
        };
        if size < header as u64 {
            return Err("bad box size".into());
        }
        // A hostile 64-bit size must not wrap around and send `at` backwards.
        let Some(end) = (at as u64).checked_add(size) else { return Err("bad box size".into()) };
        if end > to as u64 {
            return Ok((out, true));
        }
        out.push(BoxRef { start: at, header, end: end as usize, kind });
        at = end as usize;
    }
    Ok((out, at < to))
}

fn mk(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(body.len() + 8);
    v.extend_from_slice(&((body.len() + 8) as u32).to_be_bytes());
    v.extend_from_slice(kind);
    v.extend_from_slice(body);
    v
}

/// An `ilst` item: the item box around one `data` box.
fn item(kind: &[u8; 4], data_type: u32, payload: &[u8]) -> Vec<u8> {
    let mut data = Vec::with_capacity(payload.len() + 8);
    data.extend_from_slice(&data_type.to_be_bytes());
    data.extend_from_slice(&0u32.to_be_bytes()); // locale
    data.extend_from_slice(payload);
    mk(kind, &mk(b"data", &data))
}

const UTF8: u32 = 1;
const JPEG: u32 = 13;
const PNG: u32 = 14;

fn our_items(tags: &Tags, cover: Option<&[u8]>) -> Vec<([u8; 4], Vec<u8>)> {
    let mut out = Vec::new();
    let mut text = |kind: [u8; 4], v: &Option<String>| {
        if let Some(s) = v.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            out.push((kind, item(&kind, UTF8, s.as_bytes())));
        }
    };
    text(*b"\xa9nam", &tags.title);
    text(*b"\xa9ART", &tags.artist);
    text(*b"\xa9alb", &tags.album);
    text(*b"aART", &tags.album_artist);
    text(*b"\xa9day", &tags.year);
    if let Some(n) = tags.track.filter(|&n| n > 0) {
        let mut p = vec![0u8, 0];
        p.extend_from_slice(&n.to_be_bytes());
        p.extend_from_slice(&tags.track_total.unwrap_or(0).to_be_bytes());
        p.extend_from_slice(&[0, 0]);
        out.push((*b"trkn", item(b"trkn", 0, &p)));
    }
    if let Some(c) = cover.filter(|c| !c.is_empty()) {
        let t = if c.starts_with(&[0x89, b'P', b'N', b'G']) { PNG } else { JPEG };
        out.push((*b"covr", item(b"covr", t, c)));
    }
    out
}

fn hdlr_mdir() -> Vec<u8> {
    let mut body = vec![0u8; 4]; // version, flags
    body.extend_from_slice(&[0; 4]); // pre_defined
    body.extend_from_slice(b"mdir");
    body.extend_from_slice(b"appl");
    body.extend_from_slice(&[0; 8]);
    body.push(0); // empty name
    mk(b"hdlr", &body)
}

/// `ilst`: the items it had that we do not write (e.g. the encoder), then ours.
fn new_ilst(b: &[u8], old: Option<&BoxRef>, ours: &[([u8; 4], Vec<u8>)]) -> Result<Vec<u8>, String> {
    let mut body = Vec::new();
    if let Some(old) = old {
        let (items, cut) = boxes(b, old.start + old.header, old.end)?;
        if cut {
            return Err("bad ilst".into());
        }
        for i in items.iter().filter(|i| !ours.iter().any(|(k, _)| *k == i.kind)) {
            body.extend_from_slice(&b[i.start..i.end]);
        }
    }
    for (_, v) in ours {
        body.extend_from_slice(v);
    }
    Ok(mk(b"ilst", &body))
}

/// `meta` (a full box): its other children as they were, `hdlr` added when missing, `ilst` rebuilt.
fn new_meta(b: &[u8], old: Option<&BoxRef>, ours: &[([u8; 4], Vec<u8>)]) -> Result<Vec<u8>, String> {
    let mut body = vec![0u8; 4];
    let Some(old) = old else {
        body.extend_from_slice(&hdlr_mdir());
        body.extend_from_slice(&new_ilst(b, None, ours)?);
        return Ok(mk(b"meta", &body));
    };
    let inner = old.start + old.header;
    if inner + 4 > old.end {
        return Err("bad meta".into());
    }
    body.copy_from_slice(&b[inner..inner + 4]);
    let (kids, cut) = boxes(b, inner + 4, old.end)?;
    if cut {
        return Err("bad meta".into());
    }
    if !kids.iter().any(|k| &k.kind == b"hdlr") {
        body.extend_from_slice(&hdlr_mdir());
    }
    let mut ilst_done = false;
    for k in &kids {
        if &k.kind == b"ilst" {
            body.extend_from_slice(&new_ilst(b, Some(k), ours)?);
            ilst_done = true;
        } else {
            body.extend_from_slice(&b[k.start..k.end]);
        }
    }
    if !ilst_done {
        body.extend_from_slice(&new_ilst(b, None, ours)?);
    }
    Ok(mk(b"meta", &body))
}

fn new_udta(b: &[u8], old: Option<&BoxRef>, ours: &[([u8; 4], Vec<u8>)]) -> Result<Vec<u8>, String> {
    let Some(old) = old else { return Ok(mk(b"udta", &new_meta(b, None, ours)?)) };
    let (kids, cut) = boxes(b, old.start + old.header, old.end)?;
    if cut {
        return Err("bad udta".into());
    }
    let mut body = Vec::new();
    let mut meta_done = false;
    for k in &kids {
        if &k.kind == b"meta" && !meta_done {
            body.extend_from_slice(&new_meta(b, Some(k), ours)?);
            meta_done = true;
        } else {
            body.extend_from_slice(&b[k.start..k.end]);
        }
    }
    if !meta_done {
        body.extend_from_slice(&new_meta(b, None, ours)?);
    }
    Ok(mk(b"udta", &body))
}

/// True if any `stco` / `co64` under `moov` has entries: absolute offsets that a bigger `moov` would break.
fn has_chunk_offsets(b: &[u8], from: usize, to: usize, depth: u32) -> Result<bool, String> {
    // trak/mdia/minf/stbl is four levels; a file nesting far deeper is not one of YouTube's, and
    // unbounded recursion on a hostile head would overflow the stack, which no unwind catches.
    if depth > 8 {
        return Err("boxes nested too deep".into());
    }
    let (kids, _) = boxes(b, from, to)?;
    for k in kids {
        match &k.kind {
            b"stco" | b"co64" => {
                let p = k.start + k.header + 4;
                if p + 4 > k.end || u32::from_be_bytes(b[p..p + 4].try_into().unwrap()) != 0 {
                    return Ok(true);
                }
            }
            b"trak" | b"mdia" | b"minf" | b"stbl" => {
                if has_chunk_offsets(b, k.start + k.header, k.end, depth + 1)? {
                    return Ok(true);
                }
            }
            _ => {}
        }
    }
    Ok(false)
}

/// True if a `tfhd` in this `moof` carries an explicit (absolute) base data offset.
fn has_absolute_base(b: &[u8], moof: &BoxRef) -> Result<bool, String> {
    let (kids, _) = boxes(b, moof.start + moof.header, moof.end)?;
    for traf in kids.iter().filter(|k| &k.kind == b"traf") {
        let (inner, _) = boxes(b, traf.start + traf.header, traf.end)?;
        for t in inner.iter().filter(|k| &k.kind == b"tfhd") {
            let p = t.start + t.header;
            if p + 4 > t.end {
                return Err("bad tfhd".into());
            }
            let flags = u32::from_be_bytes(b[p..p + 4].try_into().unwrap()) & 0x00ff_ffff;
            if flags & 1 != 0 {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// Tags the file whose first bytes are `head` and whose last bytes are `tail` (at least 16 when
/// the file is that long). `head` must reach past `moov`; including the first `moof` lets its
/// offsets be checked too. Errors never contain the tag values.
pub fn tag_head(head: &[u8], tail: &[u8], tags: &Tags, cover: Option<&[u8]>) -> Result<Tagged, String> {
    if tail.len() >= 16 && &tail[tail.len() - 12..tail.len() - 8] == b"mfro" {
        return Err("file has an mfra index (absolute offsets)".into());
    }
    let (top, _) = boxes(head, 0, head.len())?;
    let Some(mi) = top.iter().position(|x| &x.kind == b"moov") else { return Err("no moov in the head".into()) };
    if top[..mi].iter().any(|x| &x.kind == b"mdat" || &x.kind == b"moof") {
        return Err("media before moov".into());
    }
    let moov = &top[mi];
    let (kids, cut) = boxes(head, moov.start + moov.header, moov.end)?;
    if cut {
        return Err("bad moov".into());
    }
    if !kids.iter().any(|k| &k.kind == b"mvex") {
        return Err("not a fragmented file".into());
    }
    if has_chunk_offsets(head, moov.start + moov.header, moov.end, 0)? {
        return Err("moov has chunk offsets".into());
    }
    for x in &top[mi + 1..] {
        if &x.kind == b"moof" && has_absolute_base(head, x)? {
            return Err("fragment with an absolute base offset".into());
        }
    }
    let ours = our_items(tags, cover);
    let mut body = Vec::new();
    let mut udta_done = false;
    for k in &kids {
        if &k.kind == b"udta" && !udta_done {
            body.extend_from_slice(&new_udta(head, Some(k), &ours)?);
            udta_done = true;
        } else {
            body.extend_from_slice(&head[k.start..k.end]);
        }
    }
    if !udta_done {
        body.extend_from_slice(&new_udta(head, None, &ours)?);
    }
    if body.len() + 8 > u32::MAX as usize {
        return Err("moov too large".into());
    }
    let mut out = head[..moov.start].to_vec();
    out.extend_from_slice(&mk(b"moov", &body));
    Ok(Tagged { head: out, resume_at: moov.end as u64 })
}

/// The JNI form: `[0][resume_at: u64 BE][head...]` on success, `[1][UTF-8 error]` on failure.
pub fn tag_head_packed(head: &[u8], tail: &[u8], tags_json: &str, cover: Option<&[u8]>) -> Vec<u8> {
    let result = serde_json::from_str::<Tags>(tags_json)
        .map_err(|_| "bad tags JSON".to_string())
        .and_then(|t| tag_head(head, tail, &t, cover));
    match result {
        Ok(t) => {
            let mut v = Vec::with_capacity(t.head.len() + 9);
            v.push(0);
            v.extend_from_slice(&t.resume_at.to_be_bytes());
            v.extend_from_slice(&t.head);
            v
        }
        Err(e) => {
            let mut v = vec![1u8];
            v.extend_from_slice(e.as_bytes());
            v
        }
    }
}
