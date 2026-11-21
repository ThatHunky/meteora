//! The JNI entry points for `dev.thathunky.meteora.core.MeteoraCore`. The only file that knows
//! about JNI: strings in, a JSON string out, and no panic ever crosses into the JVM.

use std::panic::{AssertUnwindSafe, catch_unwind};

use ::jni::EnvUnowned;
use ::jni::errors::{Result, ThrowRuntimeExAndDefault};
use ::jni::objects::{JClass, JString};
use ::jni::refs::Reference;
use ::jni::sys::{jboolean, jint};
use ::jni::Env;

use crate::solver;
use crate::streams::{self, err};

/// A panic becomes an error result. (`with_env` also catches one, but would throw instead.)
fn guarded(f: impl FnOnce() -> String) -> String {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| err("native core panicked"))
}

fn opt(env: &Env<'_>, s: &JString<'_>) -> Result<Option<String>> {
    if s.is_null() { Ok(None) } else { s.try_to_string(env).map(Some) }
}

/// Declares `Java_dev_thathunky_meteora_core_MeteoraCore_<name>(String...) -> String`.
macro_rules! native {
    ($name:ident, |$env:ident $(, $arg:ident : $ty:ty)*| $body:expr) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'l>(mut unowned: EnvUnowned<'l>, _class: JClass<'l> $(, $arg: $ty)*) -> JString<'l> {
            unowned
                .with_env(|$env| -> Result<JString<'l>> {
                    let out = $body;
                    JString::from_str($env, out)
                })
                .resolve::<ThrowRuntimeExAndDefault>()
        }
    };
}

native!(Java_dev_thathunky_meteora_core_MeteoraCore_ping, |env| guarded(|| streams::ok(serde_json::json!({}))));

native!(Java_dev_thathunky_meteora_core_MeteoraCore_pickAudio, |env, player: JString<'l>, tier: JString<'l>, allow_cipher: jboolean| {
    let (player, tier) = (player.try_to_string(env)?, tier.try_to_string(env)?);
    guarded(|| streams::pick_audio(&player, &tier, allow_cipher))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_pickVideo, |env, player: JString<'l>, max_height: jint, codecs: JString<'l>, allow_cipher: jboolean| {
    let (player, codecs) = (player.try_to_string(env)?, codecs.try_to_string(env)?);
    guarded(|| streams::pick_video(&player, max_height, &codecs, allow_cipher))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_parseVisitorData, |env, body: JString<'l>| {
    let body = body.try_to_string(env)?;
    guarded(|| streams::parse_visitor_data(&body))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_parsePlayerPage, |env, html: JString<'l>| {
    let html = html.try_to_string(env)?;
    guarded(|| streams::parse_player_page(&html))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_signatureTimestamp, |env, js: JString<'l>| {
    let js = js.try_to_string(env)?;
    guarded(|| streams::signature_timestamp(&js))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_streamChallenges, |env, format: JString<'l>| {
    let format = format.try_to_string(env)?;
    guarded(|| streams::stream_challenges(&format))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_buildStreamUrl, |env, format: JString<'l>, s: JString<'l>, n: JString<'l>| {
    let (format, s, n) = (format.try_to_string(env)?, opt(env, &s)?, opt(env, &n)?);
    guarded(|| streams::build_stream_url(&format, s.as_deref(), n.as_deref()))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_solverInit, |env, lib: JString<'l>, core: JString<'l>| {
    let (lib, core) = (lib.try_to_string(env)?, core.try_to_string(env)?);
    guarded(|| solver::init(lib, core))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_solverHasPlayer, |env, id: JString<'l>| {
    let id = id.try_to_string(env)?;
    guarded(|| solver::has_player(id))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_solverPrepare, |env, id: JString<'l>, js: JString<'l>| {
    let (id, js) = (id.try_to_string(env)?, js.try_to_string(env)?);
    guarded(|| solver::prepare(id, js))
});

native!(Java_dev_thathunky_meteora_core_MeteoraCore_solverSolve, |env, id: JString<'l>, sig: JString<'l>, n: JString<'l>| {
    let (id, sig, n) = (id.try_to_string(env)?, sig.try_to_string(env)?, n.try_to_string(env)?);
    guarded(|| solver::solve(id, sig, n))
});
