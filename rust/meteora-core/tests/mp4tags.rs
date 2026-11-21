//! `mp4tags` on a tiny synthetic fragmented MP4, and on a real file when `METEORA_M4A` points at one.

use meteora_core::mp4tags::{Tags, tag_head, tag_head_packed};

fn bx(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut v = ((body.len() + 8) as u32).to_be_bytes().to_vec();
    v.extend_from_slice(kind);
    v.extend_from_slice(body);
    v
}

fn full(kind: &[u8; 4], flags: u32, rest: &[u8]) -> Vec<u8> {
    let mut b = flags.to_be_bytes().to_vec();
    b.extend_from_slice(rest);
    bx(kind, &b)
}

fn data(t: u32, payload: &[u8]) -> Vec<u8> {
    let mut b = t.to_be_bytes().to_vec();
    b.extend_from_slice(&[0; 4]);
    b.extend_from_slice(payload);
    bx(b"data", &b)
}

/// ftyp, moov (mvhd, mvex, trak with an stco, udta/meta/ilst with ©too as YouTube has it), sidx, moof, mdat.
fn file(with_udta: bool, tfhd_flags: u32, stco_entries: u32, fragmented: bool) -> Vec<u8> {
    let stco = full(b"stco", 0, &stco_entries.to_be_bytes());
    let trak = bx(b"trak", &bx(b"mdia", &bx(b"minf", &bx(b"stbl", &stco))));
    let mut hdlr = vec![0u8; 4];
    hdlr.extend_from_slice(b"mdirappl");
    hdlr.extend_from_slice(&[0; 9]);
    let udta = bx(b"udta", &full(b"meta", 0, &[full(b"hdlr", 0, &hdlr), bx(b"ilst", &bx(b"\xa9too", &data(1, b"Google")))].concat()));
    let mut moov_kids = vec![full(b"mvhd", 0, &[7; 100])];
    if fragmented {
        moov_kids.push(bx(b"mvex", &full(b"trex", 0, &[0; 20])));
    }
    moov_kids.push(trak);
    if with_udta {
        moov_kids.push(udta);
    }
    let moov = bx(b"moov", &moov_kids.concat());
    let sidx = full(b"sidx", 0, &[1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]);
    let moof = bx(b"moof", &[full(b"mfhd", 0, &1u32.to_be_bytes()), bx(b"traf", &full(b"tfhd", tfhd_flags, &[0, 0, 0, 1]))].concat());
    let mdat = bx(b"mdat", &[0xAB; 64]);
    [bx(b"ftyp", b"dash\0\0\0\0iso6mp41"), moov, sidx, moof, mdat].concat()
}

fn tags() -> Tags {
    Tags {
        title: Some("Обійми".into()),
        artist: Some("Океан Ельзи".into()),
        album: Some("Земля".into()),
        album_artist: Some("Океан Ельзи".into()),
        track: Some(3),
        track_total: Some(12),
        year: Some("2013".into()),
    }
}

fn find(b: &[u8], kind: &[u8]) -> Option<usize> {
    b.windows(kind.len()).position(|w| w == kind)
}

fn count(b: &[u8], kind: &[u8]) -> usize {
    b.windows(kind.len()).filter(|w| *w == kind).count()
}

/// The payload of the `data` box of ilst item `kind`.
fn item<'a>(head: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    let at = find(head, kind)? - 4;
    let size = u32::from_be_bytes(head[at..at + 4].try_into().unwrap()) as usize;
    Some(&head[at + 8 + 16..at + size])
}

fn tail(f: &[u8]) -> &[u8] {
    &f[f.len().saturating_sub(16)..]
}

#[test]
fn adds_tags_keeps_the_rest_byte_for_byte() {
    let f = file(true, 0x02_0000, 0, true);
    let cover = [0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3];
    let t = tag_head(&f, tail(&f), &tags(), Some(&cover)).unwrap();
    let moov_at = find(&f, b"moov").unwrap() - 4;
    let moov_end = moov_at + u32::from_be_bytes(f[moov_at..moov_at + 4].try_into().unwrap()) as usize;
    assert_eq!(t.resume_at as usize, moov_end);
    // Everything before moov unchanged; the new moov's size covers exactly the new bytes.
    assert_eq!(&t.head[..moov_at], &f[..moov_at]);
    assert_eq!(u32::from_be_bytes(t.head[moov_at..moov_at + 4].try_into().unwrap()) as usize, t.head.len() - moov_at);
    assert_eq!(item(&t.head, b"\xa9nam").unwrap(), "Обійми".as_bytes());
    assert_eq!(item(&t.head, b"\xa9ART").unwrap(), "Океан Ельзи".as_bytes());
    assert_eq!(item(&t.head, b"\xa9alb").unwrap(), "Земля".as_bytes());
    assert_eq!(item(&t.head, b"aART").unwrap(), "Океан Ельзи".as_bytes());
    assert_eq!(item(&t.head, b"\xa9day").unwrap(), b"2013");
    assert_eq!(item(&t.head, b"trkn").unwrap(), &[0, 0, 0, 3, 0, 12, 0, 0]);
    assert_eq!(item(&t.head, b"covr").unwrap(), &cover);
    // The encoder tag that was there stays; still one udta, meta and hdlr.
    assert_eq!(item(&t.head, b"\xa9too").unwrap(), b"Google");
    assert_eq!(count(&t.head, b"udta"), 1);
    assert_eq!(count(&t.head, b"meta"), 1);
    assert_eq!(count(&t.head, b"hdlr"), 1);
}

#[test]
fn creates_udta_when_missing_and_retagging_replaces() {
    let f = file(false, 0x02_0000, 0, true);
    let t = tag_head(&f, tail(&f), &tags(), None).unwrap();
    assert_eq!(item(&t.head, b"\xa9nam").unwrap(), "Обійми".as_bytes());
    assert!(find(&t.head, b"mdir").is_some());
    assert!(find(&t.head, b"covr").is_none());
    // Tag the tagged file again with another title: still one title, the artist kept.
    let mut again = t.head.clone();
    again.extend_from_slice(&f[t.resume_at as usize..]);
    let t2 = tag_head(&again, tail(&again), &Tags { title: Some("Інша".into()), ..Tags::default() }, None).unwrap();
    assert_eq!(item(&t2.head, b"\xa9nam").unwrap(), "Інша".as_bytes());
    assert_eq!(count(&t2.head, b"\xa9nam"), 1);
    assert_eq!(item(&t2.head, b"\xa9ART").unwrap(), "Океан Ельзи".as_bytes());
}

#[test]
fn refuses_absolute_offsets() {
    let base_offset = file(true, 0x02_0001, 0, true);
    assert!(tag_head(&base_offset, tail(&base_offset), &tags(), None).unwrap_err().contains("absolute base"));
    let stco = file(true, 0x02_0000, 3, true);
    assert!(tag_head(&stco, tail(&stco), &tags(), None).unwrap_err().contains("chunk offsets"));
    let plain = file(true, 0x02_0000, 0, false);
    assert!(tag_head(&plain, tail(&plain), &tags(), None).unwrap_err().contains("not a fragmented"));
    let mut mfra = file(true, 0x02_0000, 0, true);
    mfra.extend_from_slice(&[0, 0, 0, 16]);
    mfra.extend_from_slice(b"mfro");
    mfra.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 40]);
    assert!(tag_head(&mfra, tail(&mfra), &tags(), None).unwrap_err().contains("mfra"));
    assert!(tag_head(b"garbage!", b"", &tags(), None).is_err());
}

#[test]
fn head_cut_inside_the_media_is_fine() {
    let f = file(true, 0x02_0000, 0, true);
    assert!(tag_head(&f[..f.len() - 10], tail(&f), &tags(), None).is_ok());
    // Cut inside moov: no moov to tag.
    let moov_at = find(&f, b"moov").unwrap();
    assert!(tag_head(&f[..moov_at + 20], tail(&f), &tags(), None).is_err());
}

#[test]
fn packed_form() {
    let f = file(true, 0x02_0000, 0, true);
    let p = tag_head_packed(&f, tail(&f), r#"{"title":"T","track":1}"#, None);
    assert_eq!(p[0], 0);
    let resume = u64::from_be_bytes(p[1..9].try_into().unwrap());
    assert!(resume > 0 && find(&p[9..], b"\xa9nam").is_some());
    let e = tag_head_packed(&f, tail(&f), "nope", None);
    assert_eq!(e[0], 1);
    assert_eq!(std::str::from_utf8(&e[1..]).unwrap(), "bad tags JSON");
}

/// `METEORA_M4A=<a real YouTube .m4a>` (and optionally `METEORA_COVER=<jpeg>`); with
/// `METEORA_M4A_OUT=<path>` the tagged file is written there for ffprobe. Skipped when unset.
#[test]
fn real_file() {
    let Ok(path) = std::env::var("METEORA_M4A") else { return };
    let f = std::fs::read(path).unwrap();
    let head = &f[..f.len().min(256 * 1024)];
    let cover = std::env::var("METEORA_COVER").ok().map(|p| std::fs::read(p).unwrap());
    let t = tag_head(head, tail(&f), &tags(), cover.as_deref()).unwrap();
    assert!(t.head.len() > t.resume_at as usize);
    let mut out = t.head.clone();
    out.extend_from_slice(&f[t.resume_at as usize..]);
    if let Ok(o) = std::env::var("METEORA_M4A_OUT") {
        std::fs::write(o, &out).unwrap();
    }
}
