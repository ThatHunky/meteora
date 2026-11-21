//! Ported from the Kotlin InnerTubeStreamsTest and WebMusicStreamsTest, on the same fixtures.

use std::path::PathBuf;

use meteora_core::streams::*;
use serde_json::{Value, json};

fn fixture(name: &str) -> String {
    let p = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../app/src/test/resources/fixtures").join(name);
    std::fs::read_to_string(p).unwrap()
}

fn ok(s: String) -> Value {
    let v: Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["ok"], true, "{s}");
    v
}

fn error(s: String) -> String {
    let v: Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["ok"], false, "{s}");
    v["error"].as_str().unwrap().to_string()
}

/// The picked format, or null.
fn pick(player: &str, tier: &str, allow_cipher: bool) -> Value {
    ok(pick_audio(player, tier, allow_cipher))["format"].clone()
}

fn response(formats: &[String]) -> String {
    format!(r#"{{"playabilityStatus":{{"status":"OK"}},"streamingData":{{"adaptiveFormats":[{}]}}}}"#, formats.join(","))
}

fn mime(codec: &str) -> String {
    let container = if codec == "opus" { "audio/webm" } else { "audio/mp4" };
    format!(r#"{container}; codecs=\"{codec}\""#)
}

fn format(itag: i32, codec: &str, bitrate: i32, extra: &str) -> String {
    format!(r#"{{"itag":{itag},"mimeType":"{}","url":"https://a/b?itag={itag}&n={bitrate}","averageBitrate":{bitrate}{extra}}}"#, mime(codec))
}

fn ciphered(itag: i32, codec: &str, bitrate: i32) -> String {
    format!(r#"{{"itag":{itag},"mimeType":"{}","signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Fr%2Fv%3Fitag%3D{itag}","averageBitrate":{bitrate}}}"#, mime(codec))
}

// ---- pick_audio ----

#[test]
fn picks_opus_first() {
    // visionOS /player for HLrqNhgdiC0, 2026-11-21, URLs replaced.
    let p = pick(&fixture("ytm/player-visionos.json"), "High", false);
    assert_eq!(p["itag"], 251);
    assert!(p["url"].as_str().unwrap().contains("itag=251"));
    assert!(p["expires_in_sec"].as_i64().unwrap() > 3600);
    assert!(p["cipher"].is_null());
}

#[test]
fn tiers_pick_from_fixture() {
    let player = fixture("ytm/player-visionos.json");
    assert_eq!(pick(&player, "High", false)["itag"], 251);
    assert_eq!(pick(&player, "Normal", false)["itag"], 251);
    assert_eq!(pick(&player, "Low", false)["itag"], 249);
    error(pick_audio(&player, "Ultra", false));
}

#[test]
fn bot_check_is_not_playable() {
    let r = ok(pick_audio(r#"{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"#, "High", false));
    assert_eq!(r["status"], "LOGIN_REQUIRED");
    assert_eq!(r["reason"], "Sign in to confirm you’re not a bot");
    assert!(r["format"].is_null());
    error(pick_audio("not json", "High", false));
    assert!(ok(pick_audio("[]", "High", false))["status"].is_null());
}

#[test]
fn ciphered_formats_are_skipped() {
    let r = r#"{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
          {"itag":251,"mimeType":"audio/webm","signatureCipher":"s=x&url=y"},
          {"itag":140,"mimeType":"audio/mp4","url":"https://a/b?itag=140","bitrate":1}]}}"#;
    assert_eq!(pick(r, "High", false)["itag"], 140);
}

#[test]
fn ciphered_premium_formats_when_allowed() {
    let r = response(&[format(251, "opus", 127_000, ""), ciphered(141, "mp4a.40.2", 257_000), ciphered(774, "opus", 258_000)]);
    let p = pick(&r, "High", true);
    assert_eq!(p["itag"], 774);
    assert!(p["url"].is_null());
    assert!(p["cipher"].as_str().unwrap().contains("itag%3D774"));
    // Without the cipher step only the plain-URL format is left.
    let plain = pick(&r, "High", false);
    assert_eq!(plain["itag"], 251);
    assert!(plain["cipher"].is_null());
    assert!(pick(&response(&[ciphered(774, "opus", 258_000)]), "High", false).is_null());
}

#[test]
fn premium_bitrates_win() {
    let opus251 = format(251, "opus", 127_000, "");
    let aac140 = format(140, "mp4a.40.2", 130_000, "");
    let aac141 = format(141, "mp4a.40.2", 256_000, "");
    let opus774 = format(774, "opus", 256_000, "");
    assert_eq!(pick(&response(&[opus251.clone(), aac140.clone(), aac141.clone(), opus774]), "High", false)["itag"], 774);
    assert_eq!(pick(&response(&[opus251, aac140, aac141]), "High", false)["itag"], 141);
}

#[test]
fn drc_variant_is_skipped() {
    let drc = r#"{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://a/drc","averageBitrate":127000,"isDrc":true}"#;
    let plain = r#"{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://a/plain","averageBitrate":127000}"#;
    assert_eq!(pick(&response(&[drc.into(), plain.into()]), "High", false)["url"], "https://a/plain");
    // Only a DRC variant: better than nothing.
    assert_eq!(pick(&response(&[drc.into()]), "High", false)["url"], "https://a/drc");
}

#[test]
fn non_default_audio_track_is_skipped() {
    let dub = format(251, "opus", 127_000, r#","audioTrack":{"audioIsDefault":false}"#);
    let orig = format(250, "opus", 70_000, r#","audioTrack":{"audioIsDefault":true}"#);
    assert_eq!(pick(&response(&[dub.clone(), orig]), "High", false)["itag"], 250);
    assert_eq!(pick(&response(&[dub]), "High", false)["itag"], 251);
}

#[test]
fn ties_go_to_the_first() {
    let a = format(251, "opus", 127_000, "");
    let b = format(774, "opus", 127_000, "");
    assert_eq!(pick(&response(&[a.clone(), b.clone()]), "High", false)["itag"], 251);
    assert_eq!(pick(&response(&[b, a]), "High", false)["itag"], 774);
    // The lowest-bitrate fallback keeps the first of equals too.
    let c = format(140, "mp4a.40.2", 130_000, "");
    let d = format(141, "mp4a.40.2", 130_000, "");
    assert_eq!(pick(&response(&[c, d]), "Low", false)["itag"], 140);
}

#[test]
fn numbers_may_be_strings() {
    let f = r#"{"itag":"251","mimeType":"audio/webm; codecs=\"opus\"","url":"https://a/x","averageBitrate":"127000","contentLength":"4000"}"#;
    let r = format!(r#"{{"playabilityStatus":{{"status":"OK"}},"streamingData":{{"expiresInSeconds":"21540","adaptiveFormats":[{f}]}}}}"#);
    let p = pick(&r, "High", false);
    assert_eq!(p["itag"], 251);
    assert_eq!(p["bitrate"], 127_000);
    assert_eq!(p["content_length"], 4000);
    assert_eq!(p["expires_in_sec"], 21540);
    // No averageBitrate: bitrate; no contentLength: null; no expiry: six hours.
    let p = pick(&response(&[r#"{"itag":140,"mimeType":"audio/mp4","url":"u","bitrate":99,"contentLength":"0"}"#.into()]), "High", false);
    assert_eq!(p["bitrate"], 99);
    assert!(p["content_length"].is_null());
    assert_eq!(p["expires_in_sec"], 21600);
}

#[test]
fn low_falls_back_to_lowest() {
    let p = pick(&response(&[format(140, "mp4a.40.2", 130_000, "")]), "Low", false);
    assert_eq!(p["itag"], 140);
    assert_eq!(p["bitrate"], 130_000);
}

// ---- visitor data, player page, signature timestamp ----

#[test]
fn visitor_data() {
    let body = ")]}'\n[[null,null,[[null,null,null,null,null,null,null,null,null,null,null,null,null,\"CgtsSVBZV0JYaC0xQSjY3ofYBjIKCgJVQRIEGgAgOQ%3D%3D\"]]]]";
    assert_eq!(ok(parse_visitor_data(body))["visitor_data"], "CgtsSVBZV0JYaC0xQSjY3ofYBjIKCgJVQRIEGgAgOQ%3D%3D");
    assert!(ok(parse_visitor_data("nope"))["visitor_data"].is_null());
    // Not at the usual place: the first Cg… string anywhere.
    let moved = r#")]}'[1,{"a":"short","b":["CgtsSVBZV0JYaC0xQSjY3ofYBjIK"]}]"#;
    assert_eq!(ok(parse_visitor_data(moved))["visitor_data"], "CgtsSVBZV0JYaC0xQSjY3ofYBjIK");
}

#[test]
fn player_page() {
    let html = r#"<script>ytcfg.set({"INNERTUBE_API_KEY":"x","INNERTUBE_CLIENT_VERSION":"1.20261119.01.00","jsUrl":"/s/player/6b3d0364/player_es6.vflset/uk_UA/base.js","x":1});</script>"#;
    let p = ok(parse_player_page(html))["page"].clone();
    assert_eq!(p["js_url"], "/s/player/6b3d0364/player_es6.vflset/uk_UA/base.js");
    assert_eq!(p["player_id"], "6b3d0364");
    assert_eq!(p["client_version"], "1.20261119.01.00");
}

#[test]
fn player_page_without_version() {
    let p = ok(parse_player_page(r#""jsUrl":"/s/player/ab_C-9/base.js""#))["page"].clone();
    assert_eq!(p["player_id"], "ab_C-9");
    // The Kotlin side has the fallback version.
    assert!(p["client_version"].is_null());
    assert!(ok(parse_player_page("<html>nothing here</html>"))["page"].is_null());
    // The id becomes a file name: nothing that could leave the folder.
    assert!(ok(parse_player_page(r#""jsUrl":"/s/player/../x/base.js""#))["page"].is_null());
    // A first jsUrl that is not a player does not hide a later one.
    let p = ok(parse_player_page(r#""jsUrl":"/other.js","jsUrl":"/s/player/abc/base.js""#))["page"].clone();
    assert_eq!(p["player_id"], "abc");
}

#[test]
fn signature_timestamps() {
    let sts = |js: &str| ok(signature_timestamp(js))["sts"].clone();
    assert_eq!(sts("var a={signatureTimestamp:20774,b:1}"), 20774);
    assert_eq!(sts("x={sts:20774}"), 20774);
    assert_eq!(sts("x={sts : 20774}"), 20774);
    assert_eq!(sts("x={sts:1}, y={sts:207745}"), 20774);
    assert!(sts("var nothing=1").is_null());
}

// ---- challenges and URL building, with a fake solver that reverses each challenge ----

fn picked(url: &str, cipher: Option<&str>) -> String {
    json!({ "itag": 774, "url": url, "cipher": cipher }).to_string()
}

fn rev(s: &Value) -> Option<String> {
    s.as_str().map(|s| s.chars().rev().collect())
}

/// What the Kotlin `decipher` does: ask for the challenges, solve them, build the URL.
fn decipher(format: &str) -> Result<(String, Value), String> {
    let c: Value = serde_json::from_str(&stream_challenges(format)).unwrap();
    if c["ok"] != true {
        return Err(c["error"].as_str().unwrap().into());
    }
    let r: Value = serde_json::from_str(&build_stream_url(format, rev(&c["s"]).as_deref(), rev(&c["n"]).as_deref())).unwrap();
    if r["ok"] != true {
        return Err(r["error"].as_str().unwrap().into());
    }
    Ok((r["url"].as_str().unwrap().into(), c))
}

#[test]
fn cipher_with_sp() {
    let (url, c) = decipher(&picked("", Some("s=abc&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D774%26n%3Dxyz"))).unwrap();
    assert_eq!(url, "https://r1.googlevideo.com/videoplayback?itag=774&n=zyx&sig=cba");
    // Both challenges in one ask.
    assert_eq!(c["s"], "abc");
    assert_eq!(c["n"], "xyz");
}

#[test]
fn cipher_without_sp_uses_signature() {
    assert_eq!(decipher(&picked("", Some("url=https%3A%2F%2Fr%2Fv%3Fitag%3D141&s=abc"))).unwrap().0, "https://r/v?itag=141&signature=cba");
}

#[test]
fn values_are_url_encoded() {
    // Decoded "a=b/c+" reversed is "+c/b=a", which must be encoded again in the URL.
    let (url, _) = decipher(&picked("", Some("s=a%3Db%2Fc%2B&sp=sig&url=https%3A%2F%2Fr%2Fv%3Fn%3Dp%252Bq%26x%3D1"))).unwrap();
    assert_eq!(url, "https://r/v?n=q%2Bp&x=1&sig=%2Bc%2Fb%3Da");
}

#[test]
fn plain_url_still_gets_n_solved() {
    let (url, c) = decipher(&picked("https://r/v?itag=251&n=abc&k=v", None)).unwrap();
    assert_eq!(url, "https://r/v?itag=251&n=cba&k=v");
    assert!(c["s"].is_null());
    assert_eq!(c["n"], "abc");
}

#[test]
fn plain_url_without_n_needs_no_solver() {
    let c = ok(stream_challenges(&picked("https://r/v?itag=251", None)));
    assert!(c["s"].is_null() && c["n"].is_null());
    assert_eq!(ok(build_stream_url(&picked("https://r/v?itag=251", None), None, None))["url"], "https://r/v?itag=251");
}

#[test]
fn unsolved_challenge_fails() {
    let f = picked("", Some("s=abc&url=https%3A%2F%2Fr%2Fv%3Fn%3Dxyz"));
    for e in [error(build_stream_url(&f, None, Some("zyx"))), error(build_stream_url(&f, Some("cba"), None))] {
        // The message never carries challenge values or the URL.
        assert!(!e.contains("abc") && !e.contains("xyz") && !e.contains("https"), "{e}");
    }
}

#[test]
fn bad_cipher_fails() {
    let e = error(stream_challenges(&picked("", Some("sp=sig"))));
    assert_eq!(e, "bad signatureCipher for itag 774");
    assert_eq!(error(stream_challenges(&picked("", None))), "no URL for itag 774");
    error(stream_challenges("not json"));
}
